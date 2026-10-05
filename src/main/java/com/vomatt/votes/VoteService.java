package com.vomatt.votes;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vomatt.votes.dto.CreateVoteRequest;
import com.vomatt.votes.dto.VoteRequest;
import com.vomatt.votes.dto.VoteResponse;
import com.vomatt.votes.dto.VoteResultResponse;
import com.vomatt.votes.dto.VoterResponse;
import com.vomatt.common.response.Cursor;
import com.vomatt.common.response.CursorResponse;
import com.vomatt.entity.User;
import com.vomatt.repository.UserRepository;
import com.vomatt.entity.Tag;
import com.vomatt.entity.UserVote;
import com.vomatt.entity.Vote;
import com.vomatt.entity.VoteOption;
import com.vomatt.entity.VoteStatus;
import com.vomatt.repository.TagRepository;
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

    public VoteResponse createVote(CreateVoteRequest request, String creatorId) {
        validateCreateVoteRequest(request);

        User creator = userRepository.findById(UUID.fromString(creatorId))
            .orElseThrow(() -> ApiException.notFound(MessageKey.USER_NOT_FOUND));

        Vote vote = new Vote(request.getTitle(), request.getDescription(), creator, request.getEndTime());
        vote.setStartTime(request.getStartTime() != null ? request.getStartTime() : OffsetDateTime.now());
        vote.setAnonymous(request.isAnonymous());
        if (request.getVoterVisibility() != null) {
            vote.setVoterVisibility(request.getVoterVisibility());
        }

        findTags(request.getTagIds()).forEach(vote::addTag);

        vote = voteRepository.save(vote);

        addOptions(vote, request);

        vote = voteRepository.save(vote);
        log.info("Vote created: {} by user: {}", vote.getId(), creatorId);

        if (request.getTagIds() != null && !request.getTagIds().isEmpty()) {
            tagRepository.incrementUsageCount(new HashSet<>(request.getTagIds()));
        }

        return convertToVoteResponse(vote);
    }

    /** @param userId viewer, or null when signed out */
    /**
     * Edits a Scheduled Poll (owner only); takes the same fields and validation as create.
     * No Ballots can exist before the start time, so options are replaced wholesale.
     */
    public VoteResponse updateVote(String voteId, CreateVoteRequest request, String userId) {
        Vote vote = findVote(UUID.fromString(voteId));
        if (!vote.getCreator().getId().toString().equals(userId)) {
            throw ApiException.forbidden(MessageKey.VOTE_FORBIDDEN);
        }
        if (vote.getStatus() != VoteStatus.SCHEDULED) {
            throw ApiException.badRequest(MessageKey.VOTE_NOT_EDITABLE);
        }
        validateCreateVoteRequest(request);

        vote.setTitle(request.getTitle());
        vote.setDescription(request.getDescription());
        vote.setStartTime(request.getStartTime() != null ? request.getStartTime() : OffsetDateTime.now());
        vote.setEndTime(request.getEndTime());
        vote.setAnonymous(request.isAnonymous());
        if (request.getVoterVisibility() != null) {
            vote.setVoterVisibility(request.getVoterVisibility());
        }

        Set<UUID> oldTagIds = vote.getTags().stream().map(Tag::getId).collect(Collectors.toSet());
        List<Tag> newTags = findTags(request.getTagIds());
        Set<UUID> newTagIds = newTags.stream().map(Tag::getId).collect(Collectors.toSet());
        vote.getTags().clear();
        newTags.forEach(vote::addTag);

        vote.getOptions().clear();
        addOptions(vote, request);
        vote = voteRepository.save(vote);

        Set<UUID> removed = new HashSet<>(oldTagIds);
        removed.removeAll(newTagIds);
        Set<UUID> added = new HashSet<>(newTagIds);
        added.removeAll(oldTagIds);
        if (!removed.isEmpty()) {
            tagRepository.decrementUsageCount(removed);
        }
        if (!added.isEmpty()) {
            tagRepository.incrementUsageCount(added);
        }
        log.info("Vote {} edited by creator {}", voteId, userId);

        return convertToVoteResponse(vote, userId);
    }

    @Transactional(readOnly = true)
    public VoteResponse getVote(String voteId, String userId) {
        Vote vote = findVote(UUID.fromString(voteId));
        return convertToVoteResponse(vote, userId);
    }

    @Transactional(readOnly = true)
    public Page<VoteResponse> getActiveVotes(Pageable pageable) {
        Page<Vote> votes = voteRepository.findActiveVotesAtTime(OffsetDateTime.now(), pageable);
        return votes.map(this::convertToVoteResponse);
    }

    @Transactional(readOnly = true)
    public Page<VoteResponse> getActiveVotesByTag(String tagSlug, Pageable pageable) {
        Page<Vote> votes = voteRepository.findByTagSlugAndIsActiveTrue(tagSlug, pageable);
        return votes.map(this::convertToVoteResponse);
    }

    @Transactional(readOnly = true)
    public Page<VoteResponse> getVotesByCreator(String creatorId, Pageable pageable) {
        Page<Vote> votes = voteRepository.findByCreatorIdOrderByCreatedAtDesc(UUID.fromString(creatorId), pageable);
        return votes.map(this::convertToVoteResponse);
    }

    /**
     * Casts a Ballot: replaces the user's previous Ballot in this Poll within one transaction.
     * Casting the same option again changes nothing.
     */
    public VoteResponse vote(String voteId, VoteRequest request, String userId, String ipAddress) {
        UUID voteUuid = UUID.fromString(voteId);
        UUID userUuid = UUID.fromString(userId);
        Vote vote = findVote(voteUuid);
        requireOpen(vote);

        if (request.getOptionIds().size() > 1) {
            throw ApiException.badRequest(MessageKey.VOTE_MULTIPLE_NOT_ALLOWED);
        }
        UUID optionUuid = UUID.fromString(request.getOptionIds().getFirst());
        VoteOption option = voteOptionRepository.findById(optionUuid)
            .orElseThrow(() -> ApiException.notFound(MessageKey.VOTE_OPTION_NOT_FOUND));
        if (!option.getVote().getId().equals(voteUuid)) {
            throw ApiException.badRequest(MessageKey.VOTE_OPTION_NOT_IN_VOTE);
        }
        User user = userRepository.findById(userUuid)
            .orElseThrow(() -> ApiException.notFound(MessageKey.USER_NOT_FOUND));

        userVoteRepository.lockBallot(userId, voteId);
        List<UserVote> previous = userVoteRepository.findByUserIdAndVoteId(userUuid, voteUuid);
        boolean unchanged = previous.size() == 1 && previous.getFirst().getOption().getId().equals(optionUuid);
        if (!unchanged) {
            List<UUID> releasedOptionIds = deleteBallots(previous);
            userVoteRepository.save(new UserVote(user, vote, option, ipAddress));
            releasedOptionIds.forEach(id -> voteOptionRepository.adjustVoteCount(id, -1));
            voteOptionRepository.adjustVoteCount(optionUuid, 1);
            log.info("User {} cast ballot on vote {} for option {}", userId, voteId, optionUuid);
        }

        return convertToVoteResponse(voteUuid, userId);
    }

    /** Retraction: withdraws the user's whole Ballot; succeeds even when there is none. */
    public VoteResponse retract(String voteId, String userId) {
        UUID voteUuid = UUID.fromString(voteId);
        requireOpen(findVote(voteUuid));

        userVoteRepository.lockBallot(userId, voteId);
        deleteBallots(userVoteRepository.findByUserIdAndVoteId(UUID.fromString(userId), voteUuid))
            .forEach(id -> voteOptionRepository.adjustVoteCount(id, -1));
        log.info("User {} retracted ballot in vote {}", userId, voteId);

        return convertToVoteResponse(voteUuid, userId);
    }

    /** Removes the user's Selection of one option (kept for API compatibility; same as a Retraction when it matches). */
    public VoteResponse removeVote(String voteId, String optionId, String userId) {
        UUID voteUuid = UUID.fromString(voteId);
        UUID userUuid = UUID.fromString(userId);
        requireOpen(findVote(voteUuid));

        userVoteRepository.lockBallot(userId, voteId);
        userVoteRepository.findByUserIdAndVoteIdAndOptionId(userUuid, voteUuid, UUID.fromString(optionId))
            .ifPresent(ballot -> deleteBallots(List.of(ballot))
                .forEach(id -> voteOptionRepository.adjustVoteCount(id, -1)));
        log.info("User {} removed vote from option {} in vote {}", userId, optionId, voteId);

        return convertToVoteResponse(voteUuid, userId);
    }

    /** Results of an Ended Poll; 403 while Sealed. */
    @Transactional(readOnly = true)
    public VoteResultResponse getVoteResults(String voteId) {
        Vote vote = findVote(UUID.fromString(voteId));
        if (vote.isResultsSealed()) {
            throw ApiException.forbidden(MessageKey.VOTE_RESULTS_SEALED);
        }
        List<VoteOption> options = voteOptionRepository.findByVoteIdOrderByDisplayOrder(vote.getId());
        return voteMapper.toResultResponse(vote, options);
    }

    /** Who chose what in an Ended Poll, as allowed by its Voter Visibility; oldest Ballot first. */
    @Transactional(readOnly = true)
    public CursorResponse<VoterResponse> getVoters(String voteId, String userId, String cursor, Integer limit) {
        Vote vote = findVote(UUID.fromString(voteId));
        if (vote.isResultsSealed()) {
            throw ApiException.forbidden(MessageKey.VOTE_RESULTS_SEALED);
        }
        boolean allowed = switch (vote.getVoterVisibility()) {
            case NOBODY -> false;
            case OWNER -> vote.getCreator().getId().toString().equals(userId);
            case SIGNED_IN -> true;
        };
        if (!allowed) {
            throw ApiException.forbidden(MessageKey.VOTE_VOTERS_HIDDEN);
        }

        int size = CursorResponse.limit(limit);
        Cursor after = Cursor.decode(cursor);
        List<UserVote> rows = userVoteRepository.findVoterPage(vote.getId(),
            after == null ? null : after.timeKey(), after == null ? null : after.id(), Limit.of(size + 1));
        return CursorResponse.of(rows, size, ballot -> Cursor.of(ballot.getCreatedAt(), ballot.getId()),
            page -> page.stream().map(voteMapper::toVoterResponse).toList());
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

        return convertToVoteResponse(vote);
    }

    private VoteResponse convertToVoteResponse(Vote vote) {
        return convertToVoteResponse(vote, null);
    }

    private VoteResponse convertToVoteResponse(UUID voteId, String userId) {
        return convertToVoteResponse(findVote(voteId), userId);
    }

    private VoteResponse convertToVoteResponse(Vote vote, String userId) {
        UUID voteId = vote.getId();
        // Use JOIN FETCH to avoid additional queries for options
        Vote voteWithOptions = voteRepository.findByIdWithOptions(voteId).orElse(vote);
        // Convert Set to sorted List, maintaining displayOrder
        List<VoteOption> options = voteWithOptions.getOptions().stream()
            .sorted(Comparator.comparingInt(VoteOption::getDisplayOrder))
            .toList();
        VoteResponse response = voteMapper.toResponse(voteWithOptions, options);
        response.setMyOptionId(userId == null ? null : findMyOptionId(voteId, UUID.fromString(userId)));
        return response;
    }

    private String findMyOptionId(UUID voteId, UUID userId) {
        return userVoteRepository.findByUserIdAndVoteId(userId, voteId).stream()
            .findFirst()
            .map(ballot -> ballot.getOption().getId().toString())
            .orElse(null);
    }

    private Vote findVote(UUID voteId) {
        return voteRepository.findById(voteId)
            .orElseThrow(() -> ApiException.notFound(MessageKey.VOTE_NOT_FOUND));
    }

    // Ballots can change only while Open; frozen once Ended
    private void requireOpen(Vote vote) {
        switch (vote.getStatus()) {
            case ENDED -> throw ApiException.badRequest(MessageKey.VOTE_ENDED);
            case SCHEDULED -> throw ApiException.badRequest(MessageKey.VOTE_NOT_ALLOWED);
            case OPEN -> { }
        }
    }

    // Deletes Ballot rows and returns the options whose counts must be released
    private List<UUID> deleteBallots(List<UserVote> ballots) {
        List<UUID> optionIds = ballots.stream().map(b -> b.getOption().getId()).toList();
        userVoteRepository.deleteAll(ballots);
        return optionIds;
    }

    private List<Tag> findTags(List<UUID> tagIds) {
        if (tagIds == null || tagIds.isEmpty()) {
            return List.of();
        }
        Set<UUID> tagUUIDs = new HashSet<>(tagIds);
        List<Tag> tags = tagRepository.findAllByIdIn(tagUUIDs);
        if (tags.size() != tagUUIDs.size()) {
            throw ApiException.badRequest(MessageKey.TAG_IDS_INVALID);
        }
        return tags;
    }

    private void addOptions(Vote vote, CreateVoteRequest request) {
        for (int i = 0; i < request.getOptions().size(); i++) {
            CreateVoteRequest.VoteOptionRequest optionRequest = request.getOptions().get(i);
            VoteOption option = new VoteOption(optionRequest.getText(), optionRequest.getDescription(), vote);
            option.setDisplayOrder(optionRequest.getDisplayOrder() != null ? optionRequest.getDisplayOrder() : i);
            vote.addOption(option);
        }
    }

    private void validateCreateVoteRequest(CreateVoteRequest request) {
        if (request.isAllowMultipleChoices()) {
            throw ApiException.badRequest(MessageKey.VOTE_MULTIPLE_NOT_ALLOWED);
        }

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
