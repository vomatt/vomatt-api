package com.vomatt.votes;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vomatt.votes.dto.CreateVoteRequest;
import com.vomatt.votes.dto.VoteRequest;
import com.vomatt.votes.dto.VoteResponse;
import com.vomatt.votes.dto.VoteResultResponse;
import com.vomatt.entity.User;
import com.vomatt.repository.UserRepository;
import com.vomatt.entity.Tag;
import com.vomatt.entity.UserVote;
import com.vomatt.entity.Vote;
import com.vomatt.entity.VoteOption;
import com.vomatt.repository.OptionVoteCount;
import com.vomatt.repository.TagRepository;
import com.vomatt.repository.UserBallot;
import com.vomatt.repository.VoteCommentRepository;
import com.vomatt.repository.VoteCount;
import com.vomatt.repository.VoteOptionCount;
import com.vomatt.repository.UserVoteRepository;
import com.vomatt.repository.VoteOptionRepository;
import com.vomatt.repository.VoteRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;


@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class VoteService {

    private final VoteRepository voteRepository;
    private final VoteOptionRepository voteOptionRepository;
    private final UserVoteRepository userVoteRepository;
    private final UserRepository userRepository;
    private final TagRepository tagRepository;
    private final VoteConfigurationProperties voteConfig;
    private final VoteMapper voteMapper;
    private final VoteCommentRepository voteCommentRepository;

    public VoteResponse createVote(CreateVoteRequest request, String creatorId) {
        validateCreateVoteRequest(request);

        User creator = userRepository.findById(UUID.fromString(creatorId))
            .orElseThrow(() -> ApiException.notFound(MessageKey.USER_NOT_FOUND));

        Vote vote = new Vote();
        vote.setCreator(creator);
        applyFields(vote, request);

        if (request.getTagIds() != null && !request.getTagIds().isEmpty()) {
            Set<UUID> tagUUIDs = new HashSet<>(request.getTagIds());
            List<Tag> tags = tagRepository.findAllByIdIn(tagUUIDs);
            if (tags.size() != tagUUIDs.size()) {
                throw ApiException.badRequest(MessageKey.TAG_IDS_INVALID);
            }
            tags.forEach(vote::addTag);
        }

        vote = voteRepository.save(vote);
        addOptions(vote, request);
        vote = voteRepository.save(vote);
        log.info("Vote created: {} by user: {}", vote.getId(), creatorId);

        if (request.getTagIds() != null && !request.getTagIds().isEmpty()) {
            tagRepository.incrementUsageCount(new HashSet<>(request.getTagIds()));
        }

        return toResponse(vote, creatorId);
    }

    /** @param viewerId the signed-in viewer, or null for a guest */
    @Transactional(readOnly = true)
    public VoteResponse getVote(String voteId, String viewerId) {
        Vote vote = voteRepository.findById(UUID.fromString(voteId))
            .orElseThrow(() -> ApiException.notFound(MessageKey.VOTE_NOT_FOUND));
        return toResponse(vote, viewerId);
    }

    @Transactional(readOnly = true)
    public Page<VoteResponse> getActiveVotes(Pageable pageable, String viewerId) {
        return toResponses(voteRepository.findActiveVotesAtTime(OffsetDateTime.now(), pageable), viewerId);
    }

    @Transactional(readOnly = true)
    public Page<VoteResponse> getActiveVotesByTag(String tagSlug, Pageable pageable, String viewerId) {
        return toResponses(voteRepository.findByTagSlugAndIsActiveTrue(tagSlug, pageable), viewerId);
    }

    /** A user's polls that weren't cancelled, ended ones included, for their public profile. */
    @Transactional(readOnly = true)
    public Page<VoteResponse> getVotesByCreatorUsername(String username, Pageable pageable, String viewerId) {
        return toResponses(voteRepository.findByCreatorUsername(username, pageable), viewerId);
    }

    @Transactional(readOnly = true)
    public Page<VoteResponse> getVotesByCreator(String creatorId, Pageable pageable) {
        Page<Vote> votes = voteRepository.findByCreatorIdOrderByCreatedAtDesc(UUID.fromString(creatorId), pageable);
        return toResponses(votes, creatorId);
    }

    /** Polls the user voted in, each with their choice in {@code myOptionId}. */
    @Transactional(readOnly = true)
    public Page<VoteResponse> getParticipatedVotes(String userId, Pageable pageable) {
        return toResponses(voteRepository.findParticipatedByUserId(UUID.fromString(userId), pageable), userId);
    }

    /**
     * The owner may edit a vote until it opens. Nobody can have voted yet, so
     * the options are replaced outright. Tags are left as they are.
     */
    public VoteResponse updateVote(String voteId, CreateVoteRequest request, String userId) {
        Vote vote = voteRepository.findById(UUID.fromString(voteId))
            .orElseThrow(() -> ApiException.notFound(MessageKey.VOTE_NOT_FOUND));

        requireCreator(vote, userId);
        if (!vote.isActive() || vote.hasStarted()) {
            throw ApiException.badRequest(MessageKey.VOTE_NOT_EDITABLE);
        }
        validateCreateVoteRequest(request);

        applyFields(vote, request);
        vote.getOptions().clear();
        addOptions(vote, request);

        vote = voteRepository.saveAndFlush(vote);
        log.info("Vote {} updated by creator {}", voteId, userId);
        return toResponse(vote, userId);
    }

    public VoteResponse vote(String voteId, VoteRequest request, String userId, String ipAddress) {
        Vote vote = voteRepository.findById(UUID.fromString(voteId))
            .orElseThrow(() -> ApiException.notFound(MessageKey.VOTE_NOT_FOUND));
        requireVotingOpen(vote);

        User user = userRepository.findById(UUID.fromString(userId))
            .orElseThrow(() -> ApiException.notFound(MessageKey.USER_NOT_FOUND));

        UUID userUuid = UUID.fromString(userId);
        UUID voteUuid = UUID.fromString(voteId);

        if (!vote.isAllowMultipleChoices()) {
            if (request.getOptionIds().size() > 1) {
                throw ApiException.badRequest(MessageKey.VOTE_MULTIPLE_NOT_ALLOWED);
            }
            userVoteRepository.deleteByUserIdAndVoteId(userUuid, voteUuid);
        }

        for (String optionId : request.getOptionIds()) {
            UUID optionUuid = UUID.fromString(optionId);
            VoteOption option = voteOptionRepository.findById(optionUuid)
                .orElseThrow(() -> ApiException.notFound(MessageKey.VOTE_OPTION_NOT_FOUND));

            if (!option.getVote().getId().toString().equals(voteId)) {
                throw ApiException.badRequest(MessageKey.VOTE_OPTION_NOT_IN_VOTE);
            }

            if (!userVoteRepository.existsByUserIdAndVoteIdAndOptionId(userUuid, voteUuid, optionUuid)) {
                UserVote userVote = new UserVote(user, vote, option, ipAddress);
                userVoteRepository.save(userVote);
            }
        }

        log.info("User {} voted on vote {} with options {}", userId, voteId, request.getOptionIds());

        return toResponse(vote, userId);
    }

    public VoteResponse removeVote(String voteId, String optionId, String userId) {
        Vote vote = voteRepository.findById(UUID.fromString(voteId))
            .orElseThrow(() -> ApiException.notFound(MessageKey.VOTE_NOT_FOUND));
        requireVotingOpen(vote);

        userVoteRepository.deleteByUserIdAndVoteIdAndOptionId(
            UUID.fromString(userId), UUID.fromString(voteId), UUID.fromString(optionId));
        log.info("User {} removed vote from option {} in vote {}", userId, optionId, voteId);

        return toResponse(vote, userId);
    }

    /**
     * Sealed (403) until the vote ends. Who chose what is shown only to the
     * creator, and never for anonymous votes.
     */
    @Transactional(readOnly = true)
    public VoteResultResponse getVoteResults(String voteId, String viewerId) {
        UUID voteUuid = UUID.fromString(voteId);
        Vote vote = voteRepository.findById(voteUuid)
            .orElseThrow(() -> ApiException.notFound(MessageKey.VOTE_NOT_FOUND));
        if (!vote.hasEnded()) {
            throw ApiException.forbidden(MessageKey.VOTE_RESULTS_SEALED);
        }

        int totalParticipants = (int) userVoteRepository.countDistinctUserByVoteId(voteUuid);
        Map<UUID, Long> optionCounts = buildOptionCountMap(voteUuid);
        long totalVoteCount = optionCounts.values().stream().mapToLong(Long::longValue).sum();
        List<VoteOption> options = voteOptionRepository.findByVoteIdOrderByDisplayOrder(voteUuid);
        boolean showVoters = vote.isCreatedBy(viewerId);

        return voteMapper.toResultResponse(vote, options, totalParticipants, optionCounts, totalVoteCount,
                showVoters);
    }

    @Transactional(readOnly = true)
    public boolean hasUserVoted(String voteId, String userId) {
        return userVoteRepository.existsByUserIdAndVoteId(UUID.fromString(userId), UUID.fromString(voteId));
    }

    @Transactional(readOnly = true)
    public List<String> getUserVoteOptions(String voteId, String userId) {
        return userVoteRepository.findByUserIdAndVoteId(UUID.fromString(userId), UUID.fromString(voteId))
            .stream()
            .map(mv -> mv.getOption().getId().toString())
            .toList();
    }

    public VoteResponse deactivateVote(String voteId, String creatorId) {
        Vote vote = voteRepository.findById(UUID.fromString(voteId))
            .orElseThrow(() -> ApiException.notFound(MessageKey.VOTE_NOT_FOUND));

        requireCreator(vote, creatorId);

        vote.deactivate();
        vote = voteRepository.save(vote);
        log.info("Vote {} deactivated by creator {}", voteId, creatorId);

        return toResponse(vote, creatorId);
    }

    /** VOTE_ENDED for a cancelled or finished vote, VOTE_NOT_ALLOWED before it opens. */
    private void requireVotingOpen(Vote vote) {
        if (vote.isVotingActive()) return;
        throw ApiException.badRequest(vote.hasEnded() ? MessageKey.VOTE_ENDED : MessageKey.VOTE_NOT_ALLOWED);
    }

    private static void requireCreator(Vote vote, String userId) {
        if (!vote.isCreatedBy(userId)) {
            throw ApiException.forbidden(MessageKey.VOTE_FORBIDDEN);
        }
    }

    /** The fields create and update share; an empty start time opens the vote now. */
    private static void applyFields(Vote vote, CreateVoteRequest request) {
        vote.setTitle(request.getTitle());
        vote.setDescription(request.getDescription());
        vote.setStartTime(request.getStartTime() != null ? request.getStartTime() : OffsetDateTime.now());
        vote.setEndTime(request.getEndTime());
        vote.setAllowMultipleChoices(request.isAllowMultipleChoices());
        vote.setAnonymous(request.isAnonymous());
    }

    /** Options keep the request's order unless it gives its own display order. */
    private static void addOptions(Vote vote, CreateVoteRequest request) {
        for (int i = 0; i < request.getOptions().size(); i++) {
            CreateVoteRequest.VoteOptionRequest optionRequest = request.getOptions().get(i);
            VoteOption option = new VoteOption(optionRequest.getText(), optionRequest.getDescription(), vote);
            option.setDisplayOrder(optionRequest.getDisplayOrder() != null ? optionRequest.getDisplayOrder() : i);
            vote.addOption(option);
        }
    }

    private VoteResponse toResponse(Vote vote, String viewerId) {
        return toResponses(List.of(vote), viewerId).getFirst();
    }

    private Page<VoteResponse> toResponses(Page<Vote> page, String viewerId) {
        return new PageImpl<>(toResponses(page.getContent(), viewerId), page.getPageable(), page.getTotalElements());
    }

    /**
     * Builds responses for a list in a fixed number of queries, however long it
     * is: votes with creator, options and tags; option counts; participant and
     * comment counts; and the viewer's own option.
     */
    private List<VoteResponse> toResponses(List<Vote> votes, String viewerId) {
        if (votes.isEmpty()) return List.of();
        List<UUID> ids = votes.stream().map(Vote::getId).toList();
        Map<UUID, Vote> loaded = voteRepository.findAllForResponseByIdIn(ids).stream()
            .collect(Collectors.toMap(Vote::getId, v -> v));
        Map<UUID, Map<UUID, Long>> optionCounts = userVoteRepository.countByOptionForVoteIds(ids).stream()
            .collect(Collectors.groupingBy(VoteOptionCount::getVoteId,
                Collectors.toMap(VoteOptionCount::getOptionId, VoteOptionCount::getCount)));
        Map<UUID, Long> participants = toCountMap(userVoteRepository.countParticipantsByVoteIds(ids));
        Map<UUID, Long> comments = toCountMap(voteCommentRepository.countByVoteIds(ids));
        Map<UUID, String> myOptions = viewerId == null ? Map.of()
            : userVoteRepository.findBallotsByUserAndVoteIds(UUID.fromString(viewerId), ids).stream()
                .collect(Collectors.toMap(UserBallot::getVoteId, b -> b.getOptionId().toString(), (a, b) -> a));

        return votes.stream().map(vote -> {
            Vote full = loaded.getOrDefault(vote.getId(), vote);
            Map<UUID, Long> counts = optionCounts.getOrDefault(vote.getId(), Map.of());
            List<VoteOption> options = full.getOptions().stream()
                .sorted(Comparator.comparingInt(VoteOption::getDisplayOrder))
                .toList();
            long totalVotes = counts.values().stream().mapToLong(Long::longValue).sum();

            VoteResponse response = voteMapper.toResponse(full, options, counts, totalVotes);
            response.setParticipantCount(participants.getOrDefault(vote.getId(), 0L));
            response.setCommentCount(comments.getOrDefault(vote.getId(), 0L));
            if (viewerId != null) response.setMyOptionId(Optional.ofNullable(myOptions.get(vote.getId())));
            return response;
        }).toList();
    }

    private static Map<UUID, Long> toCountMap(List<VoteCount> counts) {
        return counts.stream().collect(Collectors.toMap(VoteCount::getVoteId, VoteCount::getCount));
    }

    private Map<UUID, Long> buildOptionCountMap(UUID voteId) {
        return userVoteRepository.countByOptionGroupedForVote(voteId)
            .stream()
            .collect(Collectors.toMap(OptionVoteCount::getOptionId, OptionVoteCount::getCount));
    }

    private void validateCreateVoteRequest(CreateVoteRequest request) {
        if (request.getOptions().size() < voteConfig.getMinOptionsPerVote()) {
            throw ApiException.badRequest(MessageKey.VOTE_OPTIONS_MIN, voteConfig.getMinOptionsPerVote());
        }

        if (request.getOptions().size() > voteConfig.getMaxOptionsPerVote()) {
            throw ApiException.badRequest(MessageKey.VOTE_OPTIONS_MAX, voteConfig.getMaxOptionsPerVote());
        }

        if (request.getEndTime() != null) {
            if (request.getEndTime().isBefore(OffsetDateTime.now())) {
                throw ApiException.badRequest(MessageKey.VOTE_END_TIME_PAST);
            }

            if (request.getStartTime() != null && request.getEndTime().isBefore(request.getStartTime())) {
                throw ApiException.badRequest(MessageKey.VOTE_END_BEFORE_START);
            }

            OffsetDateTime maxEndTime = (request.getStartTime() != null ? request.getStartTime() : OffsetDateTime.now())
                .plus(voteConfig.getMaxVoteDuration());
            if (request.getEndTime().isAfter(maxEndTime)) {
                throw ApiException.badRequest(MessageKey.VOTE_DURATION_EXCEEDED, voteConfig.getMaxVoteDuration().toDays());
            }
        }
    }
}
