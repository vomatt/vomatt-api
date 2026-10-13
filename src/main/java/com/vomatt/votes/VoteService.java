package com.vomatt.votes;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import java.time.OffsetDateTime;
import java.util.Collection;
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
import com.vomatt.votes.dto.InviteeResponse;
import com.vomatt.votes.dto.InviteesRequest;
import com.vomatt.votes.dto.VoteRequest;
import com.vomatt.votes.dto.VoteResponse;
import com.vomatt.votes.dto.VoteResultResponse;
import com.vomatt.votes.dto.VoterResponse;
import com.vomatt.common.response.Cursor;
import com.vomatt.common.response.CursorResponse;
import com.vomatt.entity.Audience;
import com.vomatt.entity.Invitee;
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
import com.vomatt.repository.InviteeRepository;
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
    private final InviteeRepository inviteeRepository;

    public VoteResponse createVote(CreateVoteRequest request, String creatorId) {
        validateCreateVoteRequest(request, request.getStartTime());
        validateInviteeIds(request, request.getAudience());

        User creator = userRepository.findById(UUID.fromString(creatorId))
            .orElseThrow(() -> ApiException.notFound(MessageKey.USER_NOT_FOUND));

        Vote vote = new Vote(request.getTitle(), request.getDescription(), creator, request.getEndTime());
        vote.setStartTime(request.getStartTime() != null ? request.getStartTime() : OffsetDateTime.now());
        vote.setAnonymous(request.isAnonymous());
        if (request.getVoterVisibility() != null) {
            vote.setVoterVisibility(request.getVoterVisibility());
        }
        if (request.getAudience() != null) {
            vote.setAudience(request.getAudience());
        }

        findTags(request.getTagIds()).forEach(vote::addTag);

        vote = voteRepository.save(vote);

        addOptions(vote, request);

        vote = voteRepository.save(vote);
        if (request.getInviteeIds() != null) {
            insertInvitees(vote, request.getInviteeIds());
        }
        log.info("Vote created: {} by user: {}", vote.getId(), creatorId);

        Set<UUID> countedTagIds = countedTagIds(vote);
        if (!countedTagIds.isEmpty()) {
            tagRepository.incrementUsageCount(countedTagIds);
        }

        return convertToVoteResponse(vote, creatorId);
    }

    /**
     * Edits a Scheduled Poll (owner only); takes the same fields and validation as create.
     * No Ballots can exist before the start time, so options are replaced wholesale.
     */
    public VoteResponse updateVote(String voteId, CreateVoteRequest request, String userId) {
        // Exclusive lock: a first Ballot at the opening moment either commits first (then this sees Open) or waits
        Vote vote = requireOwned(voteRepository.findByIdForUpdate(UUID.fromString(voteId)), userId);
        if (vote.getStatus() != VoteStatus.SCHEDULED) {
            throw ApiException.badRequest(MessageKey.VOTE_NOT_EDITABLE);
        }
        // An omitted start time keeps the scheduled one; create's "now" default would open the Poll on edit
        OffsetDateTime startTime = request.getStartTime() != null ? request.getStartTime() : vote.getStartTime();
        validateCreateVoteRequest(request, startTime);
        Audience oldAudience = vote.getAudience();
        Audience audience = request.getAudience() != null ? request.getAudience() : oldAudience;
        validateInviteeIds(request, audience);

        vote.setTitle(request.getTitle());
        vote.setDescription(request.getDescription());
        vote.setStartTime(startTime);
        vote.scheduleEnd(request.getEndTime());
        vote.setAnonymous(request.isAnonymous());
        if (request.getVoterVisibility() != null) {
            vote.setVoterVisibility(request.getVoterVisibility());
        }

        Set<UUID> oldTagIds = countedTagIds(vote);
        if (request.getAudience() != null) {
            vote.changeAudience(request.getAudience(), OffsetDateTime.now());
        }
        vote.getTags().clear();
        findTags(request.getTagIds()).forEach(vote::addTag);
        Set<UUID> newTagIds = countedTagIds(vote);

        vote.getOptions().clear();
        addOptions(vote, request);
        vote = voteRepository.save(vote);

        // Invitees exist only on an Invite-only Poll; a null list keeps them
        if (oldAudience == Audience.INVITE_ONLY && audience != Audience.INVITE_ONLY) {
            replaceInvitees(vote, List.of());
        } else if (audience == Audience.INVITE_ONLY && request.getInviteeIds() != null) {
            replaceInvitees(vote, request.getInviteeIds());
        }

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
     * My Polls: Polls the user created, holds a Ballot in or is invited to. {@code status=open} (default) lists Scheduled
     * and Open ones closing soonest first; {@code status=ended} lists Ended ones most recently ended first. Each item
     * carries {@code unread} for the Poll's current notification.
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
        OffsetDateTime now = OffsetDateTime.now();
        List<Vote> rows = voteListRepository.findMine(userUuid, ended, Cursor.decode(cursor, scope), now, size + 1);
        return CursorResponse.of(rows, size, scope, v -> Cursor.of(v.getEndTime(), v.getId()),
            page -> withUnread(page, toResponses(page, userId), userUuid, now));
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

    // Unread when the Poll's current notification (Invitation while not Ended, then Ended Notification) is not read
    private List<VoteResponse> withUnread(List<Vote> page, List<VoteResponse> responses, UUID userId,
                                          OffsetDateTime now) {
        Set<UUID> unread = notificationReadRepository.findUnreadVoteIds(userId, page.stream().map(Vote::getId).toList(), now);
        for (int i = 0; i < page.size(); i++) {
            responses.get(i).setUnread(unread.contains(page.get(i).getId()));
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
        Vote vote = lockOpenVote(voteUuid, userId);

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

        return ballotResponse(voteUuid, userId, optionUuid);
    }

    /** Retraction: withdraws the user's whole Ballot; succeeds even when there is none. */
    public VoteResponse retract(String voteId, String userId) {
        UUID voteUuid = UUID.fromString(voteId);
        lockOpenVote(voteUuid, userId);

        userVoteRepository.lockBallot(userId, voteId);
        userVoteRepository.findByUserIdAndVoteId(UUID.fromString(userId), voteUuid)
            .ifPresent(ballot -> voteOptionRepository.adjustVoteCount(deleteBallot(ballot), -1));
        log.info("User {} retracted ballot in vote {}", userId, voteId);

        return ballotResponse(voteUuid, userId, null);
    }

    /** Removes the user's Selection of one option (kept for API compatibility; same as a Retraction when it matches). */
    public VoteResponse removeVote(String voteId, String optionId, String userId) {
        UUID voteUuid = UUID.fromString(voteId);
        UUID userUuid = UUID.fromString(userId);
        lockOpenVote(voteUuid, userId);

        userVoteRepository.lockBallot(userId, voteId);
        userVoteRepository.findByUserIdAndVoteIdAndOptionId(userUuid, voteUuid, UUID.fromString(optionId))
            .ifPresent(ballot -> voteOptionRepository.adjustVoteCount(deleteBallot(ballot), -1));
        log.info("User {} removed vote from option {} in vote {}", userId, optionId, voteId);

        return convertToVoteResponse(voteUuid, userId);
    }

    /**
     * Results of an Ended Poll; 403 while Sealed.
     *
     * @param userId viewer, or null when signed out
     */
    @Transactional(readOnly = true)
    public VoteResultResponse getVoteResults(String voteId, String userId) {
        Vote vote = findAccessibleVote(UUID.fromString(voteId), userId);
        if (vote.isResultsSealed()) {
            throw ApiException.forbidden(MessageKey.VOTE_RESULTS_SEALED);
        }
        List<VoteOption> options = voteOptionRepository.findByVoteIdOrderByDisplayOrder(vote.getId());
        return voteMapper.toResultResponse(vote, options);
    }

    /** Who chose what in an Ended Poll, as allowed by its Voter Visibility; oldest Ballot first. */
    @Transactional(readOnly = true)
    public CursorResponse<VoterResponse> getVoters(String voteId, String userId, String cursor, Integer limit) {
        Vote vote = findAccessibleVote(UUID.fromString(voteId), userId);
        if (vote.isResultsSealed()) {
            throw ApiException.forbidden(MessageKey.VOTE_RESULTS_SEALED);
        }
        boolean allowed = switch (vote.getVoterVisibility()) {
            case NOBODY -> false;
            case OWNER -> vote.isOwnedBy(userId);
            // "Anyone who can see the Poll": the access lookup above already refused everyone else
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
        findAccessibleVote(UUID.fromString(voteId), userId);
        return userVoteRepository.existsByUserIdAndVoteId(UUID.fromString(userId), UUID.fromString(voteId));
    }

    @Transactional(readOnly = true)
    public List<String> getUserVoteOptions(String voteId, String userId) {
        findAccessibleVote(UUID.fromString(voteId), userId);
        return userVoteRepository.findByUserIdAndVoteId(UUID.fromString(userId), UUID.fromString(voteId))
            .stream()
            .map(mv -> mv.getOption().getId().toString())
            .toList();
    }

    public VoteResponse closeVote(String voteId, String creatorId) {
        // Exclusive lock: waits for in-flight Ballot changes, and a concurrent Close then sees Ended and keeps its end time
        Vote vote = requireOwned(voteRepository.findByIdForUpdate(UUID.fromString(voteId)), creatorId);

        vote.closeAt(OffsetDateTime.now());
        vote = voteRepository.save(vote);
        log.info("Vote {} closed by creator {}", voteId, creatorId);

        return convertToVoteResponse(vote, creatorId);
    }

    /**
     * Delete (owner only): while Scheduled, or within the delete window of posting even when it holds Ballots.
     * The Poll is soft-deleted (data kept) and nobody is notified; its tags stop counting it.
     */
    public void deleteVote(String voteId, String userId) {
        // Exclusive lock: an in-flight Ballot finishes first, and none can land once Delete returns
        Vote vote = requireOwned(voteRepository.findByIdForUpdate(UUID.fromString(voteId)), userId);
        OffsetDateTime now = OffsetDateTime.now();
        if (!vote.canDeleteAt(now, voteConfig.getDeleteWindow())) {
            throw ApiException.badRequest(MessageKey.VOTE_DELETE_WINDOW_PASSED, voteConfig.getDeleteWindow().toMinutes());
        }
        vote.setDeletedAt(now);
        voteRepository.save(vote);
        Set<UUID> tagIds = countedTagIds(vote);
        if (!tagIds.isEmpty()) {
            tagRepository.decrementUsageCount(tagIds);
        }
        log.info("Vote {} deleted by creator {}", voteId, userId);
    }

    /** Invitees of a Poll with whether each holds a Ballot (owner only), earliest invited first. */
    @Transactional(readOnly = true)
    public List<InviteeResponse> getInvitees(String voteId, String userId) {
        return invitees(requireOwned(voteRepository.findById(UUID.fromString(voteId)), userId).getId());
    }

    /** Adds Invitees to a Scheduled or Open Invite-only Poll (owner only); users already invited are skipped. */
    public List<InviteeResponse> addInvitees(String voteId, InviteesRequest request, String userId) {
        // Exclusive lock: serializes with Invitee removal and Ballot changes, so concurrent adds cannot collide
        Vote vote = requireOwned(voteRepository.findByIdForUpdate(UUID.fromString(voteId)), userId);
        vote.checkInviteesEditable(OffsetDateTime.now());
        insertInvitees(vote, request.getUserIds());
        log.info("Invitees added to vote {} by creator {}", voteId, userId);
        return invitees(vote.getId());
    }

    /** Removes one Invitee (owner only); refused when they hold a Ballot, a no-op when they are not invited. */
    public List<InviteeResponse> removeInvitee(String voteId, String inviteeUserId, String userId) {
        // Exclusive lock: a Ballot by this Invitee either committed first (then it is seen) or waits and then loses access
        Vote vote = requireOwned(voteRepository.findByIdForUpdate(UUID.fromString(voteId)), userId);
        vote.checkInviteesEditable(OffsetDateTime.now());
        UUID inviteeUuid = UUID.fromString(inviteeUserId);
        inviteeRepository.findByVoteIdAndUserId(vote.getId(), inviteeUuid).ifPresent(invitee -> {
            if (userVoteRepository.existsByUserIdAndVoteId(inviteeUuid, vote.getId())) {
                throw ApiException.badRequest(MessageKey.VOTE_INVITEE_HAS_BALLOT);
            }
            inviteeRepository.delete(invitee);
            inviteeRepository.flush();
            log.info("Invitee {} removed from vote {} by creator {}", inviteeUserId, voteId, userId);
        });
        return invitees(vote.getId());
    }

    private List<InviteeResponse> invitees(UUID voteId) {
        return inviteeRepository.findStatuses(voteId).stream()
            .map(s -> new InviteeResponse(s.getUserId().toString(), s.getUsername(), s.getHasBallot(), s.getInvitedAt()))
            .toList();
    }

    // Duplicates, the owner (never an Invitee) and users already invited are skipped
    private void insertInvitees(Vote vote, Collection<UUID> userIds) {
        Set<UUID> ids = new HashSet<>(userIds);
        ids.remove(vote.getCreator().getId());
        if (ids.isEmpty()) {
            return;
        }
        // Suspended users cannot be invited, same as unknown ones
        List<User> users = userRepository.findAllById(ids).stream().filter(User::isActive).toList();
        if (users.size() != ids.size()) {
            throw ApiException.badRequest(MessageKey.VOTE_INVITEE_IDS_INVALID);
        }
        Set<UUID> invited = inviteeRepository.findByVoteId(vote.getId()).stream()
            .map(i -> i.getUser().getId()).collect(Collectors.toSet());
        List<User> added = users.stream().filter(u -> !invited.contains(u.getId())).toList();
        inviteeRepository.saveAllAndFlush(added.stream().map(u -> new Invitee(vote, u)).toList());
        // A removed and re-added Invitee sees the Invitation unread again
        notificationReadRepository.deleteReads(vote.getId(), added.stream().map(User::getId).toList());
    }

    // Edit only happens while Scheduled, so no Invitee can hold a Ballot yet
    private void replaceInvitees(Vote vote, List<UUID> userIds) {
        inviteeRepository.deleteAll(inviteeRepository.findByVoteId(vote.getId()).stream()
            .filter(i -> !userIds.contains(i.getUser().getId()))
            .toList());
        // Hibernate flushes inserts before deletes; flush now so a re-added user cannot hit UNIQUE (vote_id, user_id)
        inviteeRepository.flush();
        insertInvitees(vote, userIds);
    }

    /**
     * The single access lookup: every path that reads or acts on one Poll goes through here (or
     * {@link #requireAccessible}) and gets {@code vote.not_found} for a Poll the viewer may not reach.
     *
     * @param viewerId caller, or null when signed out
     */
    public Vote findAccessibleVote(UUID voteId, String viewerId) {
        return requireAccessible(voteRepository.findById(voteId), viewerId);
    }

    // The access rule itself (Vote.isVisibleTo): Deleted Polls are hidden from everyone, Invite-only ones from non-Invitees
    private Vote requireAccessible(Optional<Vote> vote, String viewerId) {
        return vote.filter(v -> v.isVisibleTo(viewerId,
                () -> inviteeRepository.existsByVoteIdAndUserId(v.getId(), UUID.fromString(viewerId))))
            .orElseThrow(() -> ApiException.notFound(MessageKey.VOTE_NOT_FOUND));
    }

    // Owner-only actions: the access lookup first (404), then 403 for anyone but the owner
    private Vote requireOwned(Optional<Vote> vote, String userId) {
        Vote owned = requireAccessible(vote, userId);
        if (!owned.isOwnedBy(userId)) {
            throw ApiException.forbidden(MessageKey.VOTE_FORBIDDEN);
        }
        return owned;
    }

    private VoteResponse convertToVoteResponse(UUID voteId, String userId) {
        // JOIN FETCH avoids additional queries for options
        Vote vote = requireAccessible(voteRepository.findByIdWithOptions(voteId), userId);
        return toResponses(List.of(vote), userId).getFirst();
    }

    private VoteResponse convertToVoteResponse(Vote vote, String userId) {
        return convertToVoteResponse(vote.getId(), userId);
    }

    // After a Ballot change the caller already knows the Selection, so it overrides the looked-up one.
    // The Poll is still reloaded: adjustVoteCount clears the persistence context and the counts changed.
    private VoteResponse ballotResponse(UUID voteId, String userId, UUID myOptionId) {
        VoteResponse response = convertToVoteResponse(voteId, userId);
        response.setMyOptionId(myOptionId == null ? null : myOptionId.toString());
        return response;
    }

    // Fixed queries per page: options / tags / creators via batch fetching, Selections and comment counts one query each
    private List<VoteResponse> toResponses(List<Vote> votes, String userId) {
        if (votes.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = votes.stream().map(Vote::getId).toList();
        OffsetDateTime now = OffsetDateTime.now();
        Map<UUID, Long> commentCounts = voteCommentRepository.countVisibleByVoteIds(ids).stream()
            .collect(Collectors.toMap(IdCount::getId, IdCount::getCount));
        Map<UUID, UUID> selections = userId == null ? Map.of()
            : userVoteRepository.findSelections(UUID.fromString(userId), ids).stream()
                .collect(Collectors.toMap(BallotSelection::getVoteId, BallotSelection::getOptionId, (a, b) -> a));
        List<UUID> inviteOnlyIds = votes.stream().filter(v -> v.getAudience() == Audience.INVITE_ONLY).map(Vote::getId).toList();
        Map<UUID, Long> inviteeCounts = inviteOnlyIds.isEmpty() ? Map.of()
            : inviteeRepository.countByVoteIds(inviteOnlyIds).stream()
                .collect(Collectors.toMap(IdCount::getId, IdCount::getCount));
        return votes.stream().map(vote -> {
            VoteResponse response = voteMapper.toResponse(vote, sortedOptions(vote));
            UUID myOptionId = selections.get(vote.getId());
            response.setMyOptionId(myOptionId == null ? null : myOptionId.toString());
            response.setCommentCount(commentCounts.getOrDefault(vote.getId(), 0L));
            if (vote.getAudience() == Audience.INVITE_ONLY) {
                response.setInviteeCount(inviteeCounts.getOrDefault(vote.getId(), 0L));
            }
            if (vote.isOwnedBy(userId)) {
                response.setDeletable(vote.canDeleteAt(now, voteConfig.getDeleteWindow()));
            }
            return response;
        }).toList();
    }

    private static List<VoteOption> sortedOptions(Vote vote) {
        return vote.getOptions().stream()
            .sorted(Comparator.comparingInt(VoteOption::getDisplayOrder))
            .toList();
    }

    /**
     * Ballots can change only while Open; frozen once Ended. Takes the shared Poll-row lock before the existing
     * (user, Poll) advisory lock and checks the status under it, so a Close or Edit cannot slip in between.
     */
    private Vote lockOpenVote(UUID voteId, String userId) {
        Vote vote = requireAccessible(voteRepository.findByIdForShare(voteId), userId);
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

    // Popular tag counts include only Public Polls
    private static Set<UUID> countedTagIds(Vote vote) {
        return vote.getAudience() != Audience.PUBLIC ? Set.of()
            : vote.getTags().stream().map(Tag::getId).collect(Collectors.toSet());
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

    private static void validateInviteeIds(CreateVoteRequest request, Audience audience) {
        if (audience != Audience.INVITE_ONLY && request.getInviteeIds() != null && !request.getInviteeIds().isEmpty()) {
            throw ApiException.badRequest(MessageKey.VOTE_INVITEE_NOT_INVITE_ONLY);
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
