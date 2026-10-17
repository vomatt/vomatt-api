package com.vomatt.votes;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.email.EmailService;
import com.vomatt.common.redis.RedisService;
import com.vomatt.common.i18n.MessageKey;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

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
import com.vomatt.groups.GroupService;
import com.vomatt.repository.TagRepository;
import com.vomatt.repository.UserPreferenceRepository;
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
    private final EmailService emailService;
    private final RedisService redisService;
    private final GroupService groupService;
    private final UserPreferenceRepository preferenceRepository;
    private final TransactionTemplate transactionTemplate;

    private static final String INVITATION_COUNTER_NS = "email:invitation";
    private static final String BALLOT_RATE_LIMIT_NS = "rate:limit:ballot";

    /**
     * k-anonymity floor for the voter list: an Ended Poll holding 1 to K-1 Anonymous Ballots hides its voter list.
     * Otherwise the owner of an Invite-only Poll could subtract the named voters from the Invitees holding a Ballot
     * and, with the per-option counts, recover who voted anonymously and for what. With K or more Anonymous Ballots
     * the owner still learns which Invitees voted anonymously and how many anonymous Ballots each option got, so if
     * all of them chose the same option their choice is still inferable.
     */
    static final int ANONYMOUS_BALLOT_K = 3;

    @Value("${app.email.invitation-daily-limit:200}")
    private int invitationDailyLimit;

    public VoteResponse createVote(CreateVoteRequest request, String creatorId) {
        validateCreateVoteRequest(request, request.getStartTime());
        validateInviteeIds(request, request.getAudience());

        User creator = userRepository.findById(UUID.fromString(creatorId))
            .orElseThrow(() -> ApiException.notFound(MessageKey.USER_NOT_FOUND));
        GroupService.Members groups = groupService.expand(orEmpty(request.getGroupIds()), creator.getId());

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
        insertInvitees(vote, concat(request.getInviteeIds(), groups.userIds()), concat(request.getInviteeEmails(), groups.emails()));
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
        GroupService.Members groups = groupService.expand(orEmpty(request.getGroupIds()), vote.getCreator().getId());

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

        // Invitees exist only on an Invite-only Poll; a null list keeps that kind of Invitee
        if (oldAudience == Audience.INVITE_ONLY && audience != Audience.INVITE_ONLY) {
            replaceInvitees(vote, List.of(), List.of());
        } else if (audience == Audience.INVITE_ONLY) {
            // Group members join the lists being set, so already-invited ones are kept rather than removed and re-added
            List<UUID> ids = request.getInviteeIds() == null ? null : concat(request.getInviteeIds(), groups.userIds());
            List<String> emails = request.getInviteeEmails() == null ? null : concat(request.getInviteeEmails(), groups.emails());
            if (ids != null || emails != null) {
                replaceInvitees(vote, ids, emails);
            }
            // Members of a kind whose list is null are added on top of the Invitees kept
            insertInvitees(vote, groups.userIds(), groups.emails());
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
     * Feed / Explore / Search / Top page: Open (newest, closing soonest or highest Turnout) or Ended Polls, optionally under one tag
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
            case TOP -> Cursor.Scope.FEED_TOP;
        };
        List<Vote> rows = voteListRepository.findPage(order, tagSlug, query, Cursor.decode(cursor, scope),
            OffsetDateTime.now(), size + 1);
        return CursorResponse.of(rows, size, scope,
            v -> switch (order) {
                case NEWEST -> Cursor.of(v.getStartTime(), v.getId());
                case CLOSING, ENDED -> Cursor.of(v.getEndTime(), v.getId());
                case TOP -> Cursor.of(v.getTurnout(), v.getStartTime(), v.getId());
            },
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
     * Casting the same option again only updates whether the Ballot is Anonymous. An omitted flag keeps the current Ballot's; a first Ballot uses the user's default.
     * The response is built after the locked write commits, so the Poll-row lock covers only the write.
     */
    @Transactional(propagation = Propagation.SUPPORTS)
    public VoteResponse vote(String voteId, VoteRequest request, String userId, String ipAddress) {
        checkBallotRateLimit(userId);
        UUID optionUuid = transactionTemplate.execute(s -> castBallot(voteId, request, userId, ipAddress));
        return transactionTemplate.execute(s -> ballotResponse(UUID.fromString(voteId), userId, optionUuid));
    }

    private UUID castBallot(String voteId, VoteRequest request, String userId, String ipAddress) {
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
            UserVote ballot = new UserVote(user, vote, option, ipAddress);
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
            // The option of an Anonymous Ballot stays out of the logs
            if (anonymous) {
                log.info("User {} cast anonymous ballot on vote {}", userId, voteId);
            } else {
                log.info("User {} cast ballot on vote {} for option {}", userId, voteId, optionUuid);
            }
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
            log.info("User {} retracted ballot in vote {}", userId, voteId);
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
                    log.info("User {} removed vote from {} in vote {}", userId,
                        ballot.isAnonymous() ? "an anonymous ballot" : "option " + optionId, voteId);
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
     * Who chose what in an Ended Poll, as allowed by its Voter Visibility; oldest Ballot first.
     * Hidden while the Poll holds 1 to {@link #ANONYMOUS_BALLOT_K}-1 Anonymous Ballots other than the caller's.
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
        // ponytail: Ballots of other accounts the caller controls (invited sock puppets) still count; no count-based gate can tell them apart.
        // An account deletion can drop Ballots between pages; the gate is re-checked on every page, so it only ever hides more.
        long anonymousBallots = userVoteRepository.countByVoteIdAndAnonymousTrueAndUserIdNot(vote.getId(), UUID.fromString(userId));
        if (anonymousBallots > 0 && anonymousBallots < ANONYMOUS_BALLOT_K) {
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
        Vote vote = requireOwned(lockVote(UUID.fromString(voteId)), creatorId);

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
        Vote vote = requireOwned(lockVote(UUID.fromString(voteId)), userId);
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
        Vote vote = requireOwned(lockVote(UUID.fromString(voteId)), userId);
        vote.checkInviteesEditable(OffsetDateTime.now());
        GroupService.Members groups = groupService.expand(orEmpty(request.getGroupIds()), vote.getCreator().getId());
        insertInvitees(vote, concat(request.getUserIds(), groups.userIds()), concat(request.getEmails(), groups.emails()));
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
        inviteeRepository.saveAllAndFlush(added);
        // A removed and re-added Invitee sees the Invitation unread again
        notificationReadRepository.deleteReads(vote.getId(),
            added.stream().filter(i -> i.getUser() != null).map(i -> i.getUser().getId()).toList());
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
        Runnable send = () -> emailService.sendInvitationEmails(recipients, inviterName, title, voteId, locale);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            send.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                send.run();
            }
        });
    }

    /**
     * Takes up to {@code n} emails from the owner's daily quota with one INCRBY; the counter moves by {@code n} even
     * over the cap, as {@code n} single increments would. Fails closed: when Redis is down no invitation is sent, but
     * the Invitees are still saved.
     */
    private int takeInvitationQuota(UUID ownerId, int n) {
        try {
            String day = LocalDate.now(ZoneOffset.UTC).toString();
            long count = redisService.incrementAndExpire(INVITATION_COUNTER_NS, ownerId + ":" + day, n, Duration.ofDays(1));
            return Math.clamp(invitationDailyLimit - (count - n), 0, n);
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

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    // Explicit Invitees plus the expanded Group members; a null explicit list counts as empty
    private static <T> List<T> concat(List<T> explicit, List<T> fromGroups) {
        List<T> all = new ArrayList<>(orEmpty(explicit));
        all.addAll(fromGroups);
        return all;
    }

    /**
     * Ties the pending email Invitees of the user's verified email to the user; each keeps its address. Called only
     * from the find-or-create by verified email in auth, inside its transaction; rerunning it binds nothing new.
     * Steps 2-4 share {@link InviteeRepository#BINDABLE}, so they agree on which rows bind.
     */
    public void bindEmailInvitees(User user) {
        String email = user.getEmail();
        // 1. Serialize with the owners' Invitee changes on those Polls
        List<UUID> voteIds = inviteeRepository.lockVotesWithPendingEmail(email);
        if (voteIds.isEmpty()) {
            return;
        }
        // 2. Invitation unread again, as for any newly added Invitee
        notificationReadRepository.deleteReadsForEmailBinding(user.getId(), email, voteIds);
        // 3. Bind
        inviteeRepository.bindEmail(user.getId(), email, voteIds);
        // 4. Pending rows already covered by a user row (or the owner's own address) go
        if (inviteeRepository.deleteUnbindableEmail(user.getId(), email, voteIds) > 0) {
            // 5. A dropped pending row may have been the only Invitee without a Ballot (binding itself adds none)
            // System path with no caller identity, so no access lookup; the Polls are locked by step 1
            voteRepository.findAllById(voteIds).forEach(this::endIfAllInviteesVoted);
        }
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
        if (myOptionId == null) {
            response.setMyBallotAnonymous(null);
        }
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
        Map<UUID, BallotSelection> selections = userId == null ? Map.of()
            : userVoteRepository.findSelections(UUID.fromString(userId), ids).stream()
                .collect(Collectors.toMap(BallotSelection::getVoteId, Function.identity(), (a, b) -> a));
        List<UUID> inviteOnlyIds = votes.stream().filter(v -> v.getAudience() == Audience.INVITE_ONLY).map(Vote::getId).toList();
        Map<UUID, Long> inviteeCounts = inviteOnlyIds.isEmpty() ? Map.of()
            : inviteeRepository.countByVoteIds(inviteOnlyIds).stream()
                .collect(Collectors.toMap(IdCount::getId, IdCount::getCount));
        return votes.stream().map(vote -> {
            VoteResponse response = voteMapper.toResponse(vote, sortedOptions(vote));
            BallotSelection mine = selections.get(vote.getId());
            response.setMyOptionId(mine == null ? null : mine.getOptionId().toString());
            response.setMyBallotAnonymous(mine == null ? null : mine.isAnonymous());
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
     * Ballots can change only while Open; frozen once Ended. Takes the exclusive Poll-row lock and checks the status
     * under it, so a Close or Edit cannot slip in between. The lock also serializes one user's Ballot changes in the Poll.
     */
    // Every Poll-row lock goes through here: a waiter past the lock timeout gets 409 instead of holding a pooled connection
    private Optional<Vote> lockVote(UUID voteId) {
        try {
            voteRepository.setLockTimeout();
            return voteRepository.findByIdForUpdate(voteId);
        } catch (PessimisticLockingFailureException e) {
            throw ApiException.conflict(MessageKey.COMMON_CONFLICT);
        }
    }

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
        if (audience != Audience.INVITE_ONLY && (!orEmpty(request.getInviteeIds()).isEmpty() || !orEmpty(request.getInviteeEmails()).isEmpty()
                || !orEmpty(request.getGroupIds()).isEmpty())) {
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
