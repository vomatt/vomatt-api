package com.vomatt.votes;

import com.vomatt.lookups.LookupService;
import com.vomatt.common.exception.ApiException;
import com.vomatt.common.util.AfterCommit;
import com.vomatt.common.util.TextUtil;
import com.vomatt.common.email.EmailService;
import com.vomatt.common.redis.RedisService;
import com.vomatt.common.i18n.MessageKey;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.vomatt.votes.dto.CreateVoteRequest;
import com.vomatt.votes.dto.InviteeResponse;
import com.vomatt.votes.dto.InviteesRequest;
import com.vomatt.votes.dto.VoteCreatorResponse;
import com.vomatt.votes.dto.VoteRequest;
import com.vomatt.votes.dto.ShareVoteRequest;
import com.vomatt.votes.dto.VoteResponse;
import com.vomatt.votes.dto.VoteShareResponse;
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
import com.vomatt.entity.VoterVisibility;
import com.vomatt.invitelists.InviteListService;
import com.vomatt.repository.TagRepository;
import com.vomatt.repository.UserPreferenceRepository;
import com.vomatt.repository.UserVoteRepository;
import com.vomatt.repository.VoteOptionRepository;
import com.vomatt.repository.BallotSelection;
import com.vomatt.repository.IdCount;
import com.vomatt.repository.InviteeRepository;
import com.vomatt.repository.LockTimeout;
import com.vomatt.repository.NotificationRepository;
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
    private final NotificationRepository notificationRepository;
    private final InviteeRepository inviteeRepository;
    private final EmailService emailService;
    private final RedisService redisService;
    private final InviteListService inviteListService;
    private final UserPreferenceRepository preferenceRepository;
    private final TransactionTemplate transactionTemplate;
    private final LockTimeout lockTimeout;
    private final LookupService lookupService;
    private final VoteViewRecorder viewRecorder;

    private static final String INVITATION_COUNTER_NS = "email:invitation";
    private static final String BALLOT_RATE_LIMIT_NS = "rate:limit:ballot";
    // Admin overrides (lookup type config); without one the app.* settings below apply
    static final String INVITATION_LIMIT_KEY = "invitation_email_daily_limit";
    static final String MAX_OPTIONS_KEY = "vote_max_options";
    static final String MAX_DURATION_DAYS_KEY = "vote_max_duration_days";
    static final String DELETE_WINDOW_MINUTES_KEY = "vote_delete_window_minutes";

    @Value("${app.email.invitation-daily-limit:200}")
    private int invitationDailyLimit;

    public VoteResponse createVote(CreateVoteRequest request, String creatorId) {
        validateCreateVoteRequest(request, request.getStartTime());
        validateInviteeIds(request, request.getAudience());

        User creator = userRepository.findById(UUID.fromString(creatorId))
            .orElseThrow(() -> ApiException.notFound(MessageKey.USER_NOT_FOUND));
        InviteListService.Members inviteLists = inviteListService.expand(orEmpty(request.getInviteListIds()), creator.getId());

        Vote vote = new Vote(request.getTitle(), request.getDescription(), creator, request.getEndTime());
        vote.setStartTime(request.getStartTime() != null ? request.getStartTime() : OffsetDateTime.now());
        applyVoterVisibility(vote, request);
        if (request.getAudience() != null) {
            vote.setAudience(request.getAudience());
        }

        findTags(request.getTagIds()).forEach(vote::addTag);

        vote = voteRepository.save(vote);

        addOptions(vote, request);

        vote = voteRepository.save(vote);
        insertInvitees(vote, concat(request.getInviteeIds(), inviteLists.userIds()), concat(request.getInviteeEmails(), inviteLists.emails()));
        log.info("Vote created: {} by user: {}", vote.getId(), creatorId);

        incrementTagUsage(countedTagIds(vote));

        return convertToVoteResponse(vote.getId(), creatorId);
    }

    /**
     * Edits a Scheduled Poll (owner only); takes the same fields and validation as create.
     * No Ballots can exist before the start time, so options are replaced wholesale.
     */
    public VoteResponse updateVote(String voteId, CreateVoteRequest request, String userId) {
        // Exclusive lock: a first Ballot at the opening moment either commits first (then this sees Open) or waits
        Vote vote = requireOwned(lockVote(UUID.fromString(voteId)), userId);
        if (vote.getStatus() != VoteStatus.SCHEDULED) {
            throw ApiException.badRequest(MessageKey.VOTE_NOT_EDITABLE);
        }
        // An omitted start time keeps the scheduled one; create's "now" default would open the Poll on edit
        OffsetDateTime startTime = request.getStartTime() != null ? request.getStartTime() : vote.getStartTime();
        validateCreateVoteRequest(request, startTime);
        Audience oldAudience = vote.getAudience();
        Audience audience = request.getAudience() != null ? request.getAudience() : oldAudience;
        validateInviteeIds(request, audience);
        InviteListService.Members inviteLists = inviteListService.expand(orEmpty(request.getInviteListIds()), vote.getCreator().getId());

        vote.setTitle(request.getTitle());
        vote.setDescription(request.getDescription());
        vote.setStartTime(startTime);
        vote.scheduleEnd(request.getEndTime());
        applyVoterVisibility(vote, request);

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

        // Invitees exist only on an Invite-only Poll; a null list keeps that kind of Invitee
        if (oldAudience == Audience.INVITE_ONLY && audience != Audience.INVITE_ONLY) {
            replaceInvitees(vote, List.of(), List.of());
        } else if (audience == Audience.INVITE_ONLY) {
            // Invite List members join the lists being set, so already-invited ones are kept rather than removed and re-added
            List<UUID> ids = request.getInviteeIds() == null ? null : concat(request.getInviteeIds(), inviteLists.userIds());
            List<String> emails = request.getInviteeEmails() == null ? null : concat(request.getInviteeEmails(), inviteLists.emails());
            if (ids != null || emails != null) {
                replaceInvitees(vote, ids, emails);
            }
            // Members of a kind whose list is null are added on top of the Invitees kept
            insertInvitees(vote, inviteLists.userIds(), inviteLists.emails());
        }

        Set<UUID> removed = new HashSet<>(oldTagIds);
        removed.removeAll(newTagIds);
        Set<UUID> added = new HashSet<>(newTagIds);
        added.removeAll(oldTagIds);
        decrementTagUsage(removed);
        incrementTagUsage(added);
        log.info("Vote {} edited by creator {}", voteId, userId);

        return convertToVoteResponse(vote.getId(), userId);
    }

    /**
     * Records that the caller shared a Poll they can reach. Idempotent per user and Poll: a repeat share keeps the first
     * channel and adds nothing to the count. Any state counts, Scheduled and Ended included.
     */
    public VoteShareResponse share(String voteId, ShareVoteRequest request, String userId) {
        Vote vote = findAccessibleVote(UUID.fromString(voteId), userId);
        if (voteRepository.insertShareIfAbsent(vote.getId(), UUID.fromString(userId), request.channel().name()) == 0) {
            return new VoteShareResponse(vote.getShareCount());
        }
        voteRepository.incrementShareCount(vote.getId());
        log.info("User {} shared vote {} via {}", userId, voteId, request.channel());
        // ponytail: count read before the increment, so a concurrent sharer's +1 may be missing until the next read
        return new VoteShareResponse(vote.getShareCount() + 1L);
    }

    /**
     * Also counts a View (asynchronously, never failing the read) when a signed-in non-owner opens an Open Public Poll,
     * the only Polls Trending can list.
     *
     * @param userId viewer, or null when signed out
     */
    @Transactional(readOnly = true)
    public VoteResponse getVote(String voteId, String userId) {
        VoteResponse response = convertToVoteResponse(UUID.fromString(voteId), userId);
        if (userId != null && !userId.equals(response.getCreatorId()) && response.getAudience() == Audience.PUBLIC
                && response.isVotingActive()) {
            viewRecorder.record(UUID.fromString(response.getId()), UUID.fromString(userId));
        }
        return response;
    }

    /**
     * The owner of an Invite-only Poll, for an Invitation link opened by someone who cannot reach the Poll yet.
     * Deliberately skips the access lookup; any other Poll (Deleted, Public, Unlisted, unknown) is {@code vote.not_found}.
     */
    @Transactional(readOnly = true)
    public VoteCreatorResponse getInviteOnlyCreator(String voteId) {
        User creator = voteRepository.findById(UUID.fromString(voteId))
            .filter(v -> v.getDeletedAt() == null && v.getAudience() == Audience.INVITE_ONLY)
            .map(Vote::getCreator)
            .orElseThrow(() -> ApiException.notFound(MessageKey.VOTE_NOT_FOUND));
        return new VoteCreatorResponse(creator.getUsername(), creator.getAvatarUrl());
    }

    /**
     * Feed / Explore / Search / Top page: Open (newest, closing soonest or highest Turnout) or Ended Polls, optionally under one tag
     * and matching a free-text query on title and description. A signed-in caller does not see Polls of users they blocked.
     */
    @Transactional(readOnly = true)
    public CursorResponse<VoteResponse> listVotes(VoteListOrder order, String tag, String q, String cursor,
                                                  Integer limit, String userId) {
        int size = CursorResponse.limit(limit);
        String tagSlug = TextUtil.blankToNull(tag);
        String query = searchQuery(q);
        Cursor.Scope scope = switch (order) {
            case NEWEST -> Cursor.Scope.FEED_NEWEST;
            case CLOSING -> Cursor.Scope.FEED_CLOSING;
            case ENDED -> Cursor.Scope.FEED_ENDED;
            case TOP -> Cursor.Scope.FEED_TOP;
        };
        List<Vote> rows = voteListRepository.findPage(order, tagSlug, query, Cursor.decode(cursor, scope),
            OffsetDateTime.now(), size + 1, userId == null ? null : UUID.fromString(userId));
        return CursorResponse.of(rows, size, scope,
            v -> switch (order) {
                case NEWEST -> Cursor.of(v.getStartTime(), v.getId());
                case CLOSING, ENDED -> Cursor.of(v.getEndTime(), v.getId());
                case TOP -> Cursor.of(v.getTurnout(), v.getStartTime(), v.getId());
            },
            page -> toResponses(page, userId));
    }

    /** Following list: Open PUBLIC Polls created by users the caller follows, most recently opened first. */
    @Transactional(readOnly = true)
    public CursorResponse<VoteResponse> listFollowingVotes(String userId, String cursor, Integer limit) {
        int size = CursorResponse.limit(limit);
        List<Vote> rows = voteListRepository.findFollowing(UUID.fromString(userId),
            Cursor.decode(cursor, Cursor.Scope.FEED_FOLLOWING), OffsetDateTime.now(), size + 1);
        return CursorResponse.of(rows, size, Cursor.Scope.FEED_FOLLOWING, v -> Cursor.of(v.getStartTime(), v.getId()),
            page -> toResponses(page, userId));
    }

    /**
     * My Polls: Polls the user created, holds a Ballot in or is invited to. {@code status=open} (default) lists Scheduled
     * and Open ones closing soonest first; {@code status=ended} lists Ended ones most recently ended first. Each item
     * carries {@code unread} for the Poll's current notification.
     */
    @Transactional(readOnly = true)
    public CursorResponse<VoteResponse> getMyPolls(String userId, String status, String cursor, Integer limit) {
        boolean ended = switch (status == null ? VoteStatus.OPEN : VoteStatus.parse(status)) {
            case OPEN -> false;
            case ENDED -> true;
            case SCHEDULED -> throw ApiException.badRequest(MessageKey.COMMON_INVALID_STATUS, status);
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
        User creator = userRepository.findActiveByUsername(username)
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
        Set<UUID> unread = notificationRepository.findUnreadVoteIds(userId, page.stream().map(Vote::getId).toList(), now);
        for (int i = 0; i < page.size(); i++) {
            responses.get(i).setUnread(unread.contains(page.get(i).getId()));
        }
        return responses;
    }

    /**
     * Casts a Ballot: replaces the user's previous Ballot in this Poll within one transaction.
     * Casting the same option again only updates whether the Ballot is Anonymous. An omitted flag keeps the current Ballot's; a first Ballot uses the user's default.
     * The response is built after the locked write commits, so the Poll-row lock covers only the write.
     */
    @Transactional(propagation = Propagation.SUPPORTS)
    public VoteResponse vote(String voteId, VoteRequest request, String userId) {
        checkBallotRateLimit(userId);
        UUID optionUuid = transactionTemplate.execute(s -> castBallot(voteId, request, userId));
        return transactionTemplate.execute(s -> ballotResponse(UUID.fromString(voteId), userId, optionUuid));
    }

    private UUID castBallot(String voteId, VoteRequest request, String userId) {
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
        // findById, not getReferenceById: an account deleted while its token is still valid gets 404, not an FK error
        User user = userRepository.findById(userUuid)
            .orElseThrow(() -> ApiException.notFound(MessageKey.USER_NOT_FOUND));

        Optional<UserVote> previous = userVoteRepository.findByUserIdAndVoteId(userUuid, voteUuid);
        // An omitted flag keeps the current Ballot's, so a client that does not resend it never unmasks the user
        boolean anonymous = request.getAnonymous() != null ? request.getAnonymous()
            : previous.map(UserVote::isAnonymous).orElseGet(() -> preferenceRepository.anonymousBallotByDefault(userUuid));
        boolean unchanged = previous.filter(b -> b.getOption().getId().equals(optionUuid)).isPresent();
        if (unchanged) {
            previous.get().setAnonymous(anonymous);
        } else {
            Optional<UUID> releasedOptionId = previous.map(this::deleteBallot);
            UserVote ballot = new UserVote(user, vote, option);
            ballot.setAnonymous(anonymous);
            userVoteRepository.saveAndFlush(ballot);
            // Before the count updates below: each clears the persistence context, detaching the locked Poll, and the
            // first one flushes its end
            endIfAllInviteesVoted(vote);
            releasedOptionId.ifPresent(id -> voteOptionRepository.adjustVoteCount(id, -1));
            voteOptionRepository.adjustVoteCount(optionUuid, 1);
            if (previous.isEmpty()) {
                voteRepository.adjustTurnout(voteUuid, 1);
            }
            // Ballot logs name only the Poll and the event: never who voted nor what they chose, so log platforms
            // (looser access than the DB, long retention) cannot rebuild Ballots
            log.info("ballot.cast vote={} anonymous={}", voteId, anonymous);
        }
        return optionUuid;
    }

    /** Retraction: withdraws the user's whole Ballot; succeeds even when there is none. */
    @Transactional(propagation = Propagation.SUPPORTS)
    public VoteResponse retract(String voteId, String userId) {
        checkBallotRateLimit(userId);
        UUID voteUuid = UUID.fromString(voteId);
        transactionTemplate.execute(s -> {
            lockOpenVote(voteUuid, userId);
            userVoteRepository.findByUserIdAndVoteId(UUID.fromString(userId), voteUuid)
                .ifPresent(this::releaseBallot);
            log.info("ballot.retract vote={}", voteId);
            return null;
        });
        return transactionTemplate.execute(s -> ballotResponse(voteUuid, userId, null));
    }

    /** Removes the user's Selection of one option (kept for API compatibility; same as a Retraction when it matches). */
    @Transactional(propagation = Propagation.SUPPORTS)
    public VoteResponse removeVote(String voteId, String optionId, String userId) {
        checkBallotRateLimit(userId);
        UUID voteUuid = UUID.fromString(voteId);
        UUID userUuid = UUID.fromString(userId);
        transactionTemplate.execute(s -> {
            lockOpenVote(voteUuid, userId);
            userVoteRepository.findByUserIdAndVoteIdAndOptionId(userUuid, voteUuid, UUID.fromString(optionId))
                .ifPresent(ballot -> {
                    releaseBallot(ballot);
                    log.info("ballot.retract vote={}", voteId);
                });
            return null;
        });
        return transactionTemplate.execute(s -> convertToVoteResponse(voteUuid, userId));
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

    /**
     * Who took part in an Ended Poll, as allowed by its Voter Visibility; oldest Ballot first. Anonymous Ballots are
     * left out, and while the Poll holds any (other than the caller's own) no option is named at all: with the named
     * voters' options and the per-option totals, subtraction would otherwise tell what the anonymous ones chose.
     */
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
        // The caller's own Anonymous Ballot does not count: they know its option, so it hides no one else's.
        // ponytail: Ballots of other accounts the caller controls (invited sock puppets) still count; no count-based rule can tell them apart.
        // Re-checked on every page: an account deletion can drop Ballots between pages, so this only ever hides more.
        boolean withOptions = userVoteRepository.countByVoteIdAndAnonymousTrueAndUserIdNot(vote.getId(), UUID.fromString(userId)) == 0;

        int size = CursorResponse.limit(limit);
        Cursor after = Cursor.decode(cursor, Cursor.Scope.VOTERS);
        List<UserVote> rows = userVoteRepository.findVoterPage(vote.getId(),
            after == null ? null : after.timeKey(), after == null ? null : after.id(), Limit.of(size + 1));
        return CursorResponse.of(rows, size, Cursor.Scope.VOTERS, ballot -> Cursor.of(ballot.getCreatedAt(), ballot.getId()),
            page -> page.stream().map(ballot -> voteMapper.toVoterResponse(ballot, withOptions)).toList());
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
            // A sealed Anonymous Ballot (Ended Poll) has no option any more
            .filter(mv -> mv.getOption() != null)
            .map(mv -> mv.getOption().getId().toString())
            .toList();
    }

    public VoteResponse closeVote(String voteId, String creatorId) {
        // Exclusive lock: waits for in-flight Ballot changes, and a concurrent Close then sees Ended and keeps its end time
        Vote vote = requireOwned(lockVote(UUID.fromString(voteId)), creatorId);

        vote.closeAt(OffsetDateTime.now());
        vote = voteRepository.save(vote);
        log.info("Vote {} closed by creator {}", voteId, creatorId);

        return convertToVoteResponse(vote.getId(), creatorId);
    }

    /**
     * Delete (owner only): while Scheduled, or within the delete window of posting even when it holds Ballots.
     * The Poll is soft-deleted (data kept) and nobody is notified; its tags stop counting it.
     */
    public void deleteVote(String voteId, String userId) {
        // Exclusive lock: an in-flight Ballot finishes first, and none can land once Delete returns
        Vote vote = requireOwned(lockVote(UUID.fromString(voteId)), userId);
        OffsetDateTime now = OffsetDateTime.now();
        Duration deleteWindow = deleteWindow();
        if (!vote.canDeleteAt(now, deleteWindow)) {
            throw ApiException.badRequest(MessageKey.VOTE_DELETE_WINDOW_PASSED, deleteWindow.toMinutes());
        }
        softDelete(vote, now);
        log.info("Vote {} deleted by creator {}", voteId, userId);
    }

    /**
     * The one soft delete (owner and admin): the row and its Ballots are kept and its tags stop counting it. A Poll
     * already Deleted is left as is. Callers check their own rules and hold the Poll-row lock.
     */
    public void softDelete(Vote vote, OffsetDateTime now) {
        if (vote.getDeletedAt() != null) {
            return;
        }
        vote.setDeletedAt(now);
        voteRepository.save(vote);
        decrementTagUsage(countedTagIds(vote));
    }

    /** Inverse of {@link #softDelete}: the Poll is visible again and its tags count it again; a live Poll is left as is. */
    public void restore(Vote vote) {
        if (vote.getDeletedAt() == null) {
            return;
        }
        vote.setDeletedAt(null);
        voteRepository.save(vote);
        incrementTagUsage(countedTagIds(vote));
    }

    /** Invitees of a Poll with whether each holds a Ballot (owner only), earliest invited first. */
    @Transactional(readOnly = true)
    public List<InviteeResponse> getInvitees(String voteId, String userId) {
        return invitees(requireOwned(voteRepository.findById(UUID.fromString(voteId)), userId).getId());
    }

    /** Adds Invitees to a Scheduled or Open Invite-only Poll (owner only); users already invited are skipped. */
    public List<InviteeResponse> addInvitees(String voteId, InviteesRequest request, String userId) {
        // Exclusive lock: serializes with Invitee removal and Ballot changes, so concurrent adds cannot collide
        Vote vote = requireOwned(lockVote(UUID.fromString(voteId)), userId);
        vote.checkInviteesEditable(OffsetDateTime.now());
        InviteListService.Members inviteLists = inviteListService.expand(orEmpty(request.getInviteListIds()), vote.getCreator().getId());
        insertInvitees(vote, concat(request.getUserIds(), inviteLists.userIds()), concat(request.getEmails(), inviteLists.emails()));
        log.info("Invitees added to vote {} by creator {}", voteId, userId);
        return invitees(vote.getId());
    }

    /** Removes one Invitee added by ID (owner only); refused when they hold a Ballot, a no-op when they are not invited. */
    public List<InviteeResponse> removeInvitee(String voteId, String inviteeUserId, String userId) {
        UUID inviteeUuid = UUID.fromString(inviteeUserId);
        return removeInvitee(voteId, userId, id -> inviteeRepository.findByVoteIdAndUserIdAndEmailIsNull(id, inviteeUuid));
    }

    /** Removes one Invitee added by email (owner only); refused when they hold a Ballot, a no-op when the address is not invited. */
    public List<InviteeResponse> removeEmailInvitee(String voteId, String email, String userId) {
        String normalized = User.normalizeEmail(email);
        return removeInvitee(voteId, userId, id -> inviteeRepository.findByVoteIdAndEmail(id, normalized));
    }

    // The one removal path for both kinds of Invitee
    private List<InviteeResponse> removeInvitee(String voteId, String userId, Function<UUID, Optional<Invitee>> findInvitee) {
        // Exclusive lock: a Ballot by this Invitee either committed first (then it is seen) or waits and then loses access
        Vote vote = requireOwned(lockVote(UUID.fromString(voteId)), userId);
        vote.checkInviteesEditable(OffsetDateTime.now());
        findInvitee.apply(vote.getId()).ifPresent(invitee -> {
            // An email Invitee holds a Ballot only once tied to a user
            if (invitee.getUser() != null && userVoteRepository.existsByUserIdAndVoteId(invitee.getUser().getId(), vote.getId())) {
                throw ApiException.badRequest(MessageKey.VOTE_INVITEE_HAS_BALLOT);
            }
            inviteeRepository.delete(invitee);
            inviteeRepository.flush();
            log.info("Invitee {} removed from vote {} by creator {}", invitee.getId(), voteId, userId);
        });
        endIfAllInviteesVoted(vote);
        return invitees(vote.getId());
    }

    /**
     * Auto-end, run under the Poll-row lock after a Ballot is cast or an Invitee removed (by the owner or by email binding): the next Ballot then hits the
     * Ended guard in {@link #lockOpenVote}. The owner is never an Invitee, so their Ballot never counts.
     *
     * @param vote managed, so the end is flushed with the transaction
     */
    private void endIfAllInviteesVoted(Vote vote) {
        if (vote.endIfAllInviteesVotedAt(OffsetDateTime.now(), () -> inviteeRepository.allHoldBallot(vote.getId()))) {
            log.info("Vote {} ended: every Invitee holds a Ballot", vote.getId());
        }
    }

    private List<InviteeResponse> invitees(UUID voteId) {
        return inviteeRepository.findStatuses(voteId).stream()
            // An Invitee added by email is shown only by that address, never revealing whether it has an account
            .map(s -> s.getEmail() != null
                ? new InviteeResponse(null, null, null, s.getEmail(), s.getHasBallot(), s.getInvitedAt())
                : new InviteeResponse(s.getUserId().toString(), s.getUsername(), s.getAvatarUrl(), null, s.getHasBallot(), s.getInvitedAt()))
            .toList();
    }

    /**
     * Duplicates, the owner (never an Invitee) and anyone already invited are skipped. An email row keeps the address;
     * one of an active account also holds that user, so they get access right away. Any other address (unknown or
     * suspended) stays pending until verified. Every new email row is sent an invitation once committed.
     */
    private void insertInvitees(Vote vote, Collection<UUID> userIds, Collection<String> emails) {
        UUID ownerId = vote.getCreator().getId();
        Set<UUID> ids = new HashSet<>(userIds);
        ids.remove(ownerId);
        Set<String> newEmails = emails.stream().map(User::normalizeEmail).collect(Collectors.toCollection(HashSet::new));
        if (ids.isEmpty() && newEmails.isEmpty()) {
            return;
        }
        // Suspended users cannot be invited by ID, same as unknown ones
        List<User> users = ids.isEmpty() ? List.of() : userRepository.findAllById(ids).stream().filter(User::isActive).toList();
        if (users.size() != ids.size()) {
            throw ApiException.badRequest(MessageKey.VOTE_INVITEE_IDS_INVALID);
        }
        List<Invitee> invited = inviteeRepository.findByVoteId(vote.getId());
        Set<UUID> invitedUsers = invited.stream().filter(i -> i.getUser() != null)
            .map(i -> i.getUser().getId()).collect(Collectors.toCollection(HashSet::new));
        invited.forEach(i -> newEmails.remove(i.getEmail()));
        List<Invitee> added = new ArrayList<>();
        users.stream().filter(u -> invitedUsers.add(u.getId())).forEach(u -> added.add(new Invitee(vote, u)));
        Map<String, User> accounts = newEmails.isEmpty() ? Map.of() : userRepository.findByEmailIn(newEmails).stream()
            .filter(User::isActive).collect(Collectors.toMap(User::getEmail, u -> u));
        for (String email : newEmails) {
            User account = accounts.get(email);
            if (account == null) {
                added.add(new Invitee(vote, email));
            } else if (!account.getId().equals(ownerId) && invitedUsers.add(account.getId())) {
                added.add(new Invitee(vote, account, email));
            }
        }
        if (added.isEmpty()) {
            return;
        }
        // A removed and re-added Invitee is a new row, so their Invitation is a new notification, unread
        inviteeRepository.saveAllAndFlush(added);
        sendInvitations(vote, added.stream().map(Invitee::getEmail).filter(Objects::nonNull).toList());
    }

    /**
     * Edit only happens while Scheduled, so no Invitee can hold a Ballot yet. A null list keeps that kind of Invitee.
     * An Invitee added by email is kept when its stored address is listed; one added by ID when the user is listed in
     * either list (by ID, or by the email of their account). Kept ones are never deleted and re-added, so only truly
     * new Invitees see the Invitation unread again.
     */
    private void replaceInvitees(Vote vote, List<UUID> userIds, List<String> emails) {
        Set<String> keptEmails = emails == null ? null : emails.stream().map(User::normalizeEmail).collect(Collectors.toSet());
        Set<UUID> keptUsers = userIds == null ? null : new HashSet<>(userIds);
        if (keptUsers != null && keptEmails != null && !keptEmails.isEmpty()) {
            userRepository.findByEmailIn(keptEmails).forEach(u -> keptUsers.add(u.getId()));
        }
        inviteeRepository.deleteAll(inviteeRepository.findByVoteId(vote.getId()).stream()
            .filter(i -> i.getEmail() != null
                ? keptEmails != null && !keptEmails.contains(i.getEmail())
                : keptUsers != null && !keptUsers.contains(i.getUser().getId()))
            .toList());
        // Hibernate flushes inserts before deletes; flush now so a re-added Invitee cannot hit a unique constraint
        inviteeRepository.flush();
        insertInvitees(vote, orEmpty(userIds), orEmpty(emails));
    }

    /**
     * Sends each address the Poll's invitation at most once, ever (callers hold the Poll lock or own a new Poll, so the
     * check and record cannot race), and at most {@code invitationDailyLimit} per owner per UTC day. Over the cap the
     * Invitee is kept and only the email is skipped. A fixed number of round trips per call however many addresses;
     * sent after commit, so a rolled-back request sends nothing, by one background task mailing them one at a time.
     * Recorded as sent before mailing (at most once): a failed send or a crash mid-loop is not retried.
     */
    private void sendInvitations(Vote vote, List<String> emails) {
        if (emails.isEmpty()) {
            return;
        }
        User owner = vote.getCreator();
        UUID voteId = vote.getId();
        List<String> unsent = new ArrayList<>(emails);
        unsent.removeAll(inviteeRepository.findInvitationsSent(voteId, emails));
        if (unsent.isEmpty()) {
            return;
        }
        int allowed = takeInvitationQuota(owner.getId(), unsent.size());
        if (allowed < unsent.size()) {
            log.warn("Invitation email daily limit reached: {} invitation(s) for vote {} by owner {} not sent",
                unsent.size() - allowed, voteId, owner.getId());
        }
        if (allowed == 0) {
            return;
        }
        List<String> recipients = List.copyOf(unsent.subList(0, allowed));
        inviteeRepository.recordInvitationsSent(voteId, recipients.toArray(String[]::new));
        String inviterName = owner.getDisplayName() != null ? owner.getDisplayName() : owner.getUsername();
        String title = vote.getTitle();
        Locale locale = LocaleContextHolder.getLocale();
        AfterCommit.run(() -> emailService.sendInvitationEmails(recipients, inviterName, title, voteId, locale));
    }

    /**
     * Takes up to {@code n} emails from the owner's daily quota with one INCRBY; the counter moves by {@code n} even
     * over the cap, as {@code n} single increments would. Fails closed: when Redis is down no invitation is sent, but
     * the Invitees are still saved.
     */
    private int takeInvitationQuota(UUID ownerId, int n) {
        // Read before counting and outside the catch: a failing lookup query must not be swallowed inside this transaction
        int limit = lookupService.configInt(INVITATION_LIMIT_KEY, invitationDailyLimit);
        try {
            String day = TextUtil.utcDay();
            long count = redisService.incrementAndExpire(INVITATION_COUNTER_NS, ownerId + ":" + day, n, Duration.ofDays(1));
            return Math.clamp(limit - (count - n), 0, n);
        } catch (RuntimeException e) {
            log.warn("Invitation email limit check failed for owner {}: {}", ownerId, e.getMessage());
            return 0;
        }
    }

    // Runs before any row lock so throttled requests never touch the Poll; fails open like RateLimitFilter's write path
    private void checkBallotRateLimit(String userId) {
        VoteConfigurationProperties.BallotRateLimit limit = voteConfig.getBallotRateLimit();
        long count;
        try {
            count = redisService.incrementAndExpire(BALLOT_RATE_LIMIT_NS, userId, limit.getWindow());
        } catch (RuntimeException e) {
            log.warn("Ballot rate limit check failed for user {}: {}", userId, e.getMessage());
            return;
        }
        if (count > limit.getCapacity()) {
            throw ApiException.tooManyRequests(MessageKey.COMMON_RATE_LIMITED);
        }
    }

    // is_anonymous mirrors NOBODY so the legacy column never disagrees with Voter Visibility
    private static void applyVoterVisibility(Vote vote, CreateVoteRequest request) {
        vote.setVoterVisibility(request.resolveVoterVisibility(vote.getVoterVisibility()));
        vote.setAnonymous(vote.getVoterVisibility() == VoterVisibility.NOBODY);
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    // Explicit Invitees plus the expanded Invite List members; a null explicit list counts as empty
    private static <T> List<T> concat(List<T> explicit, List<T> fromInviteLists) {
        List<T> all = new ArrayList<>(orEmpty(explicit));
        all.addAll(fromInviteLists);
        return all;
    }

    /**
     * Ties the pending email Invitees of the user's verified email to the user; each keeps its address. Called only
     * from the find-or-create by verified email in auth, inside its transaction; rerunning it binds nothing new.
     * Steps 2-3 share {@link InviteeRepository#BINDABLE}, so they agree on which rows bind.
     */
    public void bindEmailInvitees(User user) {
        String email = user.getEmail();
        // 1. Serialize with the owners' Invitee changes on those Polls
        List<UUID> voteIds = inviteeRepository.lockVotesWithPendingEmail(email);
        if (voteIds.isEmpty()) {
            return;
        }
        // 2. Bind; the Invitation is the Invitee row's, never read by this user before, so it arrives unread
        inviteeRepository.bindEmail(user.getId(), email, voteIds);
        // 3. Pending rows already covered by a user row (or the owner's own address) go
        if (inviteeRepository.deleteUnbindableEmail(user.getId(), email, voteIds) > 0) {
            // 4. A dropped pending row may have been the only Invitee without a Ballot (binding itself adds none)
            // System path with no caller identity, so no access lookup; the Polls are locked by step 1
            voteRepository.findAllById(voteIds).forEach(this::endIfAllInviteesVoted);
        }
    }

    /**
     * The single access lookup: every path that reads or acts on one Poll goes through here (or
     * {@link #requireAccessible}) and gets {@code vote.not_found} for a Poll the viewer may not reach.
     * The one exception is {@link #getInviteOnlyCreator}, which reveals only an Invite-only Poll's owner.
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

    // After a Ballot change the caller already knows the Selection, so it overrides the looked-up one.
    // The Poll is still reloaded: adjustVoteCount clears the persistence context and the counts changed.
    private VoteResponse ballotResponse(UUID voteId, String userId, UUID myOptionId) {
        VoteResponse response = convertToVoteResponse(voteId, userId);
        response.setMyOptionId(myOptionId == null ? null : myOptionId.toString());
        if (myOptionId == null) {
            response.setMyBallotAnonymous(null);
        }
        return response;
    }

    /**
     * The one Poll-to-response path (Sealed via {@link VoteMapper}). Fixed queries per page: options / tags / creators via
     * batch fetching, Selections and comment counts one query each.
     *
     * @param userId viewer, or null for none (no Selection, no {@code deletable}), as for admin views
     */
    public List<VoteResponse> toResponses(List<Vote> votes, String userId) {
        if (votes.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = votes.stream().map(Vote::getId).toList();
        OffsetDateTime now = OffsetDateTime.now();
        Map<UUID, Long> commentCounts = voteCommentRepository.countVisibleByVoteIds(ids).stream()
            .collect(Collectors.toMap(IdCount::getId, IdCount::getCount));
        Map<UUID, BallotSelection> selections = userId == null ? Map.of()
            : userVoteRepository.findSelections(UUID.fromString(userId), ids).stream()
                .collect(Collectors.toMap(BallotSelection::getVoteId, Function.identity(), (a, b) -> a));
        List<UUID> inviteOnlyIds = votes.stream().filter(v -> v.getAudience() == Audience.INVITE_ONLY).map(Vote::getId).toList();
        Map<UUID, Long> inviteeCounts = inviteOnlyIds.isEmpty() ? Map.of()
            : inviteeRepository.countByVoteIds(inviteOnlyIds).stream()
                .collect(Collectors.toMap(IdCount::getId, IdCount::getCount));
        // Read only when the page holds the caller's own Polls (the only ones that get deletable)
        Duration deleteWindow = votes.stream().anyMatch(v -> v.isOwnedBy(userId)) ? deleteWindow() : null;
        return votes.stream().map(vote -> {
            VoteResponse response = voteMapper.toResponse(vote, sortedOptions(vote));
            BallotSelection mine = selections.get(vote.getId());
            // A sealed Anonymous Ballot (Ended Poll) has no option any more; the Ballot itself is still the caller's
            response.setMyOptionId(mine == null || mine.getOptionId() == null ? null : mine.getOptionId().toString());
            response.setMyBallotAnonymous(mine == null ? null : mine.isAnonymous());
            response.setCommentCount(commentCounts.getOrDefault(vote.getId(), 0L));
            if (vote.getAudience() == Audience.INVITE_ONLY) {
                response.setInviteeCount(inviteeCounts.getOrDefault(vote.getId(), 0L));
            }
            if (vote.isOwnedBy(userId)) {
                response.setDeletable(vote.canDeleteAt(now, deleteWindow));
            }
            return response;
        }).toList();
    }

    /** Admin override {@code config / vote_delete_window_minutes}, else {@code app.vote.delete-window} (ADR 0004). */
    private Duration deleteWindow() {
        return lookupService.configDuration(DELETE_WINDOW_MINUTES_KEY, ChronoUnit.MINUTES, voteConfig.getDeleteWindow());
    }

    private static List<VoteOption> sortedOptions(Vote vote) {
        return vote.getOptions().stream()
            .sorted(Comparator.comparingInt(VoteOption::getDisplayOrder))
            .toList();
    }

    /**
     * Every Poll-row lock goes through here (owner and admin paths): a waiter past the lock timeout gets 409
     * {@code common.conflict} instead of holding a pooled connection. Must be the first load of the Poll in the transaction.
     */
    public Optional<Vote> lockVote(UUID voteId) {
        lockTimeout.apply();
        return voteRepository.findByIdForUpdate(voteId);
    }

    /**
     * Ballots can change only while Open; frozen once Ended. Takes the exclusive Poll-row lock and checks the status
     * under it, so a Close or Edit cannot slip in between. The lock also serializes one user's Ballot changes in the Poll.
     */
    private Vote lockOpenVote(UUID voteId, String userId) {
        Vote vote = requireAccessible(lockVote(voteId), userId);
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

    // Withdraws a whole Ballot: its option count and the Poll's Turnout both drop by one
    private void releaseBallot(UserVote ballot) {
        UUID voteId = ballot.getVote().getId();
        voteOptionRepository.adjustVoteCount(deleteBallot(ballot), -1);
        voteRepository.adjustTurnout(voteId, -1);
    }

    /**
     * Poll search text ({@code q}) for the bigram index: stripped, null when blank, 400
     * {@code vote.search.query_too_short} for one character, which has no bigrams (ADR 0001).
     */
    public static String searchQuery(String q) {
        String query = TextUtil.blankToNull(q);
        if (query != null && query.codePointCount(0, query.length()) < 2) {
            throw ApiException.badRequest(MessageKey.VOTE_SEARCH_QUERY_TOO_SHORT);
        }
        return query;
    }

    // Popular tag counts include only Public Polls
    private static Set<UUID> countedTagIds(Vote vote) {
        return vote.getAudience() != Audience.PUBLIC || vote.getTags() == null ? Set.of()
            : vote.getTags().stream().map(Tag::getId).collect(Collectors.toSet());
    }

    private void incrementTagUsage(Set<UUID> tagIds) {
        if (!tagIds.isEmpty()) {
            tagRepository.incrementUsageCount(tagIds);
        }
    }

    private void decrementTagUsage(Set<UUID> tagIds) {
        if (!tagIds.isEmpty()) {
            tagRepository.decrementUsageCount(tagIds);
        }
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
        if (audience != Audience.INVITE_ONLY && (!orEmpty(request.getInviteeIds()).isEmpty() || !orEmpty(request.getInviteeEmails()).isEmpty()
                || !orEmpty(request.getInviteListIds()).isEmpty())) {
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

        // Never below the minimum, or no Poll could be created
        int maxOptions = Math.max(lookupService.configInt(MAX_OPTIONS_KEY, voteConfig.getMaxOptionsPerVote()),
                voteConfig.getMinOptionsPerVote());
        if (request.getOptions().size() > maxOptions) {
            throw ApiException.badRequest(MessageKey.VOTE_OPTIONS_MAX, maxOptions);
        }

        if (request.getEndTime() != null) {
            if (request.getEndTime().isBefore(OffsetDateTime.now())) {
                throw ApiException.badRequest(MessageKey.VOTE_END_TIME_PAST);
            }

            // equal start and end would never open (and would read as a cancelled Scheduled Poll)
            if (startTime != null && !request.getEndTime().isAfter(startTime)) {
                throw ApiException.badRequest(MessageKey.VOTE_END_BEFORE_START);
            }

            Duration maxDuration = lookupService.configDuration(MAX_DURATION_DAYS_KEY, ChronoUnit.DAYS, voteConfig.getMaxVoteDuration());
            OffsetDateTime maxEndTime = (startTime != null ? startTime : OffsetDateTime.now()).plus(maxDuration);
            if (request.getEndTime().isAfter(maxEndTime)) {
                throw ApiException.badRequest(MessageKey.VOTE_DURATION_EXCEEDED, maxDuration.toDays());
            }
        }
    }
}
