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

        Vote vote = new Vote(request.getTitle(), request.getDescription(), creator, request.getEndTime());
        vote.setStartTime(request.getStartTime() != null ? request.getStartTime() : OffsetDateTime.now());
        vote.setAllowMultipleChoices(request.isAllowMultipleChoices());
        vote.setAnonymous(request.isAnonymous());

        if (request.getTagIds() != null && !request.getTagIds().isEmpty()) {
            Set<UUID> tagUUIDs = new HashSet<>(request.getTagIds());
            List<Tag> tags = tagRepository.findAllByIdIn(tagUUIDs);
            if (tags.size() != tagUUIDs.size()) {
                throw ApiException.badRequest(MessageKey.TAG_IDS_INVALID);
            }
            tags.forEach(vote::addTag);
        }

        vote = voteRepository.save(vote);

        for (int i = 0; i < request.getOptions().size(); i++) {
            CreateVoteRequest.VoteOptionRequest optionRequest = request.getOptions().get(i);
            VoteOption option = new VoteOption(optionRequest.getText(), optionRequest.getDescription(), vote);
            option.setDisplayOrder(optionRequest.getDisplayOrder() != null ? optionRequest.getDisplayOrder() : i);
            vote.addOption(option);
        }

        vote = voteRepository.save(vote);
        log.info("Vote created: {} by user: {}", vote.getId(), creatorId);

        if (request.getTagIds() != null && !request.getTagIds().isEmpty()) {
            tagRepository.incrementUsageCount(new HashSet<>(request.getTagIds()));
        }

        return toResponses(List.of(vote), creatorId).getFirst();
    }

    @Transactional(readOnly = true)
    /** @param viewerId the signed-in viewer, or null for a guest */
    public VoteResponse getVote(String voteId, String viewerId) {
        Vote vote = voteRepository.findById(UUID.fromString(voteId))
            .orElseThrow(() -> ApiException.notFound(MessageKey.VOTE_NOT_FOUND));
        return toResponses(List.of(vote), viewerId).getFirst();
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

        if (!vote.getCreator().getId().toString().equals(userId)) {
            throw ApiException.forbidden(MessageKey.VOTE_FORBIDDEN);
        }
        if (!vote.isActive() || vote.getStartTime() == null || !OffsetDateTime.now().isBefore(vote.getStartTime())) {
            throw ApiException.badRequest(MessageKey.VOTE_NOT_EDITABLE);
        }
        validateCreateVoteRequest(request);

        vote.setTitle(request.getTitle());
        vote.setDescription(request.getDescription());
        vote.setStartTime(request.getStartTime() != null ? request.getStartTime() : OffsetDateTime.now());
        vote.setEndTime(request.getEndTime());
        vote.setAllowMultipleChoices(request.isAllowMultipleChoices());
        vote.setAnonymous(request.isAnonymous());

        vote.getOptions().clear();
        for (int i = 0; i < request.getOptions().size(); i++) {
            CreateVoteRequest.VoteOptionRequest optionRequest = request.getOptions().get(i);
            VoteOption option = new VoteOption(optionRequest.getText(), optionRequest.getDescription(), vote);
            option.setDisplayOrder(optionRequest.getDisplayOrder() != null ? optionRequest.getDisplayOrder() : i);
            vote.addOption(option);
        }

        vote = voteRepository.saveAndFlush(vote);
        log.info("Vote {} updated by creator {}", voteId, userId);
        return toResponses(List.of(vote), userId).getFirst();
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

        return toResponses(List.of(vote), userId).getFirst();
    }

    public VoteResponse removeVote(String voteId, String optionId, String userId) {
        Vote vote = voteRepository.findById(UUID.fromString(voteId))
            .orElseThrow(() -> ApiException.notFound(MessageKey.VOTE_NOT_FOUND));
        requireVotingOpen(vote);

        userVoteRepository.deleteByUserIdAndVoteIdAndOptionId(
            UUID.fromString(userId), UUID.fromString(voteId), UUID.fromString(optionId));
        log.info("User {} removed vote from option {} in vote {}", userId, optionId, voteId);

        return toResponses(List.of(vote), userId).getFirst();
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
        long totalVoteCount = userVoteRepository.countByVoteId(voteUuid);
        Map<UUID, Long> optionCounts = buildOptionCountMap(voteUuid);
        List<VoteOption> options = voteOptionRepository.findByVoteIdOrderByDisplayOrder(voteUuid);
        boolean showVoters = vote.getCreator().getId().toString().equals(viewerId);

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

        if (!vote.getCreator().getId().toString().equals(creatorId)) {
            throw ApiException.forbidden(MessageKey.VOTE_FORBIDDEN);
        }

        vote.deactivate();
        vote = voteRepository.save(vote);
        log.info("Vote {} deactivated by creator {}", voteId, creatorId);

        return toResponses(List.of(vote), creatorId).getFirst();
    }

    /** VOTE_ENDED for a cancelled or finished vote, VOTE_NOT_ALLOWED before it opens. */
    private void requireVotingOpen(Vote vote) {
        if (vote.isVotingActive()) return;
        throw ApiException.badRequest(vote.hasEnded() ? MessageKey.VOTE_ENDED : MessageKey.VOTE_NOT_ALLOWED);
    }

    private Page<VoteResponse> toResponses(Page<Vote> page, String viewerId) {
        return new PageImpl<>(toResponses(page.getContent(), viewerId), page.getPageable(), page.getTotalElements());
    }

    /**
     * Adds what a list needs beyond one vote: participant and comment counts,
     * and the viewer's own option, each fetched once for the whole list.
     */
    private List<VoteResponse> toResponses(List<Vote> votes, String viewerId) {
        if (votes.isEmpty()) return List.of();
        List<UUID> ids = votes.stream().map(Vote::getId).toList();
        Map<UUID, Long> participants = toCountMap(userVoteRepository.countParticipantsByVoteIds(ids));
        Map<UUID, Long> comments = toCountMap(voteCommentRepository.countByVoteIds(ids));
        Map<UUID, String> myOptions = viewerId == null ? Map.of()
            : userVoteRepository.findBallotsByUserAndVoteIds(UUID.fromString(viewerId), ids).stream()
                .collect(Collectors.toMap(UserBallot::getVoteId, b -> b.getOptionId().toString(), (a, b) -> a));

        return votes.stream().map(vote -> {
            VoteResponse response = convertToVoteResponse(vote);
            response.setParticipantCount(participants.getOrDefault(vote.getId(), 0L));
            response.setCommentCount(comments.getOrDefault(vote.getId(), 0L));
            if (viewerId != null) response.setMyOptionId(Optional.ofNullable(myOptions.get(vote.getId())));
            return response;
        }).toList();
    }

    private static Map<UUID, Long> toCountMap(List<VoteCount> counts) {
        return counts.stream().collect(Collectors.toMap(VoteCount::getVoteId, VoteCount::getCount));
    }

    private VoteResponse convertToVoteResponse(Vote vote) {
        UUID voteId = vote.getId();
        // Use JOIN FETCH to avoid additional queries for options
        Vote voteWithOptions = voteRepository.findByIdWithOptions(voteId).orElse(vote);
        // Convert Set to sorted List, maintaining displayOrder
        List<VoteOption> options = voteWithOptions.getOptions().stream()
            .sorted(Comparator.comparingInt(VoteOption::getDisplayOrder))
            .toList();
        Map<UUID, Long> optionCounts = buildOptionCountMap(voteId);
        // Sum from optionCounts to avoid querying total vote count again
        long totalVoteCount = optionCounts.values().stream().mapToLong(Long::longValue).sum();
        VoteResponse response = voteMapper.toResponse(voteWithOptions, options, optionCounts, totalVoteCount);
        // Sealed ballot: per-option counts stay hidden until the vote ends
        if (!vote.hasEnded()) response.getOptions().forEach(option -> option.setVotes(null));
        return response;
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
