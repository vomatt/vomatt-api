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

import org.springframework.data.domain.Limit;
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
import com.vomatt.repository.BallotSelection;
import com.vomatt.repository.IdCount;
import com.vomatt.repository.NotificationReadRepository;
import com.vomatt.repository.VoteCommentRepository;
import com.vomatt.repository.VoteListRepository;
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
    private final VoteListRepository voteListRepository;
    private final VoteCommentRepository voteCommentRepository;
    private final NotificationReadRepository notificationReadRepository;

    public VoteResponse createVote(CreateVoteRequest request, String creatorId) {
        validateCreateVoteRequest(request, request.getStartTime());

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

    /**
     * Edits a Scheduled Poll (owner only); takes the same fields and validation as create.
     * No Ballots can exist before the start time, so options are replaced wholesale.
     */
    public VoteResponse updateVote(String voteId, CreateVoteRequest request, String userId) {
        // Exclusive lock: a first Ballot at the opening moment either commits first (then this sees Open) or waits
        Vote vote = voteRepository.findByIdForUpdate(UUID.fromString(voteId))
            .orElseThrow(() -> ApiException.notFound(MessageKey.VOTE_NOT_FOUND));
        if (!vote.getCreator().getId().toString().equals(userId)) {
            throw ApiException.forbidden(MessageKey.VOTE_FORBIDDEN);
        }
        if (vote.getStatus() != VoteStatus.SCHEDULED) {
            throw ApiException.badRequest(MessageKey.VOTE_NOT_EDITABLE);
        }
        // An omitted start time keeps the scheduled one; create's "now" default would open the Poll on edit
        OffsetDateTime startTime = request.getStartTime() != null ? request.getStartTime() : vote.getStartTime();
        validateCreateVoteRequest(request, startTime);

        vote.setTitle(request.getTitle());
        vote.setDescription(request.getDescription());
        vote.setStartTime(startTime);
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

    /** @param userId viewer, or null when signed out */
    @Transactional(readOnly = true)
    public VoteResponse getVote(String voteId, String userId) {
        return convertToVoteResponse(UUID.fromString(voteId), userId);
    }

    /**
     * Feed / Explore / Search page: Open (newest or closing soonest) or Ended Polls, optionally under one tag
     * and matching a free-text query on title and description.
     */
    @Transactional(readOnly = true)
    public CursorResponse<VoteResponse> listVotes(VoteListOrder order, String tag, String q, String cursor,
                                                  Integer limit, String userId) {
        int size = CursorResponse.limit(limit);
        String tagSlug = tag == null || tag.isBlank() ? null : tag;
        String query = q == null || q.isBlank() ? null : q.strip();
        // one-character queries cannot use the bigram index (ADR 0001)
        if (query != null && query.codePointCount(0, query.length()) < 2) {
            throw ApiException.badRequest(MessageKey.VOTE_SEARCH_QUERY_TOO_SHORT);
        }
        Cursor.Scope scope = switch (order) {
            case NEWEST -> Cursor.Scope.FEED_NEWEST;
            case CLOSING -> Cursor.Scope.FEED_CLOSING;
            case ENDED -> Cursor.Scope.FEED_ENDED;
        };
        List<Vote> rows = voteListRepository.findPage(order, tagSlug, query, Cursor.decode(cursor, scope),
            OffsetDateTime.now(), size + 1);
        return CursorResponse.of(rows, size, scope,
            v -> Cursor.of(order == VoteListOrder.NEWEST ? v.getStartTime() : v.getEndTime(), v.getId()),
            page -> toResponses(page, userId));
    }

    /**
     * My Polls: Polls the user created or holds a Ballot in. {@code status=open} (default) lists Scheduled and
     * Open ones closing soonest first; {@code status=ended} lists Ended ones most recently ended first.
     */
    @Transactional(readOnly = true)
    public CursorResponse<VoteResponse> getMyPolls(String userId, String status, String cursor, Integer limit) {
        boolean ended = switch (status == null ? "open" : status) {
            case "open" -> false;
            case "ended" -> true;
            default -> throw ApiException.badRequest(MessageKey.COMMON_INVALID_STATUS, status);
        };
        int size = CursorResponse.limit(limit);
        UUID userUuid = UUID.fromString(userId);
        Cursor.Scope scope = ended ? Cursor.Scope.MY_ENDED : Cursor.Scope.MY_OPEN;
        List<Vote> rows = voteListRepository.findMine(userUuid, ended, Cursor.decode(cursor, scope),
            OffsetDateTime.now(), size + 1);
        return CursorResponse.of(rows, size, scope, v -> Cursor.of(v.getEndTime(), v.getId()),
            page -> ended ? withUnread(page, toResponses(page, userId), userUuid) : toResponses(page, userId));
    }

    /**
     * A user's public profile list: their Open and Ended Polls, most recently opened first (cursor-paged).
     * Scheduled Polls and Polls Cancelled before opening are left out.
     *
     * @param userId viewer, or null when signed out
     */
    @Transactional(readOnly = true)
    public CursorResponse<VoteResponse> getUserPolls(String username, String cursor, Integer limit, String userId) {
        // Suspended users are hidden, same as user search
        User creator = userRepository.findByUsername(username).filter(User::isActive)
            .orElseThrow(() -> ApiException.notFound(MessageKey.USER_NOT_FOUND));
        int size = CursorResponse.limit(limit);
        List<Vote> rows = voteListRepository.findByCreator(creator.getId(), Cursor.decode(cursor, Cursor.Scope.USER_POLLS),
            OffsetDateTime.now(), size + 1);
        return CursorResponse.of(rows, size, Cursor.Scope.USER_POLLS, v -> Cursor.of(v.getStartTime(), v.getId()),
            page -> toResponses(page, userId));
    }

    // Ended tab: unread when the Poll produced an Ended Notification (it opened) that the user hasn't read
    private List<VoteResponse> withUnread(List<Vote> page, List<VoteResponse> responses, UUID userId) {
        Set<UUID> read = notificationReadRepository.findReadVoteIds(userId, page.stream().map(Vote::getId).toList());
        for (int i = 0; i < page.size(); i++) {
            Vote vote = page.get(i);
            responses.get(i).setUnread(!vote.isCancelledBeforeOpening() && !read.contains(vote.getId()));
        }
        return responses;
    }

    /**
     * Casts a Ballot: replaces the user's previous Ballot in this Poll within one transaction.
     * Casting the same option again changes nothing.
     */
    public VoteResponse vote(String voteId, VoteRequest request, String userId, String ipAddress) {
        UUID voteUuid = UUID.fromString(voteId);
        UUID userUuid = UUID.fromString(userId);
        Vote vote = lockOpenVote(voteUuid);

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
        Optional<UserVote> previous = userVoteRepository.findByUserIdAndVoteId(userUuid, voteUuid);
        boolean unchanged = previous.filter(b -> b.getOption().getId().equals(optionUuid)).isPresent();
        if (!unchanged) {
            Optional<UUID> releasedOptionId = previous.map(this::deleteBallot);
            userVoteRepository.save(new UserVote(user, vote, option, ipAddress));
            releasedOptionId.ifPresent(id -> voteOptionRepository.adjustVoteCount(id, -1));
            voteOptionRepository.adjustVoteCount(optionUuid, 1);
            log.info("User {} cast ballot on vote {} for option {}", userId, voteId, optionUuid);
        }

        return ballotResponse(voteUuid, optionUuid);
    }

    /** Retraction: withdraws the user's whole Ballot; succeeds even when there is none. */
    public VoteResponse retract(String voteId, String userId) {
        UUID voteUuid = UUID.fromString(voteId);
        lockOpenVote(voteUuid);

        userVoteRepository.lockBallot(userId, voteId);
        userVoteRepository.findByUserIdAndVoteId(UUID.fromString(userId), voteUuid)
            .ifPresent(ballot -> voteOptionRepository.adjustVoteCount(deleteBallot(ballot), -1));
        log.info("User {} retracted ballot in vote {}", userId, voteId);

        return ballotResponse(voteUuid, null);
    }

    /** Removes the user's Selection of one option (kept for API compatibility; same as a Retraction when it matches). */
    public VoteResponse removeVote(String voteId, String optionId, String userId) {
        UUID voteUuid = UUID.fromString(voteId);
        UUID userUuid = UUID.fromString(userId);
        lockOpenVote(voteUuid);

        userVoteRepository.lockBallot(userId, voteId);
        userVoteRepository.findByUserIdAndVoteIdAndOptionId(userUuid, voteUuid, UUID.fromString(optionId))
            .ifPresent(ballot -> voteOptionRepository.adjustVoteCount(deleteBallot(ballot), -1));
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
        Cursor after = Cursor.decode(cursor, Cursor.Scope.VOTERS);
        List<UserVote> rows = userVoteRepository.findVoterPage(vote.getId(),
            after == null ? null : after.timeKey(), after == null ? null : after.id(), Limit.of(size + 1));
        return CursorResponse.of(rows, size, Cursor.Scope.VOTERS, ballot -> Cursor.of(ballot.getCreatedAt(), ballot.getId()),
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
        // Exclusive lock: waits for in-flight Ballot changes, and a concurrent Close then sees Ended and keeps its end time
        Vote vote = voteRepository.findByIdForUpdate(UUID.fromString(voteId))
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
        // JOIN FETCH avoids additional queries for options
        Vote vote = voteRepository.findByIdWithOptions(voteId)
            .orElseThrow(() -> ApiException.notFound(MessageKey.VOTE_NOT_FOUND));
        return toResponses(List.of(vote), userId).getFirst();
    }

    private VoteResponse convertToVoteResponse(Vote vote, String userId) {
        return convertToVoteResponse(vote.getId(), userId);
    }

    // After a Ballot change the caller already knows the Selection, so skip looking it up.
    // The Poll is still reloaded: adjustVoteCount clears the persistence context and the counts changed.
    private VoteResponse ballotResponse(UUID voteId, UUID myOptionId) {
        VoteResponse response = convertToVoteResponse(voteId, null);
        response.setMyOptionId(myOptionId == null ? null : myOptionId.toString());
        return response;
    }

    // Fixed queries per page: options / tags / creators via batch fetching, Selections and comment counts one query each
    private List<VoteResponse> toResponses(List<Vote> votes, String userId) {
        if (votes.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = votes.stream().map(Vote::getId).toList();
        Map<UUID, Long> commentCounts = voteCommentRepository.countVisibleByVoteIds(ids).stream()
            .collect(Collectors.toMap(IdCount::getId, IdCount::getCount));
        Map<UUID, UUID> selections = userId == null ? Map.of()
            : userVoteRepository.findSelections(UUID.fromString(userId), ids).stream()
                .collect(Collectors.toMap(BallotSelection::getVoteId, BallotSelection::getOptionId, (a, b) -> a));
        return votes.stream().map(vote -> {
            VoteResponse response = voteMapper.toResponse(vote, sortedOptions(vote));
            UUID myOptionId = selections.get(vote.getId());
            response.setMyOptionId(myOptionId == null ? null : myOptionId.toString());
            response.setCommentCount(commentCounts.getOrDefault(vote.getId(), 0L));
            return response;
        }).toList();
    }

    private static List<VoteOption> sortedOptions(Vote vote) {
        return vote.getOptions().stream()
            .sorted(Comparator.comparingInt(VoteOption::getDisplayOrder))
            .toList();
    }

    private Vote findVote(UUID voteId) {
        return voteRepository.findById(voteId)
            .orElseThrow(() -> ApiException.notFound(MessageKey.VOTE_NOT_FOUND));
    }

    /**
     * Ballots can change only while Open; frozen once Ended. Takes the shared Poll-row lock before the existing
     * (user, Poll) advisory lock and checks the status under it, so a Close or Edit cannot slip in between.
     */
    private Vote lockOpenVote(UUID voteId) {
        Vote vote = voteRepository.findByIdForShare(voteId)
            .orElseThrow(() -> ApiException.notFound(MessageKey.VOTE_NOT_FOUND));
        switch (vote.getStatus()) {
            case ENDED -> throw ApiException.badRequest(MessageKey.VOTE_ENDED);
            case SCHEDULED -> throw ApiException.badRequest(MessageKey.VOTE_NOT_ALLOWED);
            case OPEN -> { }
        }
        return vote;
    }

    // Deletes the Ballot row and returns the option whose count must be released
    private UUID deleteBallot(UserVote ballot) {
        userVoteRepository.delete(ballot);
        // Hibernate flushes inserts before deletes; flush now so a replacing Ballot cannot hit UNIQUE (user_id, vote_id)
        userVoteRepository.flush();
        return ballot.getOption().getId();
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

    // startTime null means the Poll opens now
    private void validateCreateVoteRequest(CreateVoteRequest request, OffsetDateTime startTime) {
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

            // equal start and end would never open (and would read as a cancelled Scheduled Poll)
            if (startTime != null && !request.getEndTime().isAfter(startTime)) {
                throw ApiException.badRequest(MessageKey.VOTE_END_BEFORE_START);
            }

            OffsetDateTime maxEndTime = (startTime != null ? startTime : OffsetDateTime.now())
                .plus(voteConfig.getMaxVoteDuration());
            if (request.getEndTime().isAfter(maxEndTime)) {
                throw ApiException.badRequest(MessageKey.VOTE_DURATION_EXCEEDED, voteConfig.getMaxVoteDuration().toDays());
            }
        }
    }
}
