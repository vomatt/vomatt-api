package com.vomatt.votes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.context.annotation.Import;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.entity.Audience; import com.vomatt.entity.Invitee;
import com.vomatt.entity.User;
import com.vomatt.entity.Vote;
import com.vomatt.repository.PostgresRepositoryTest;
import com.vomatt.repository.VoteOptionRepository;
import com.vomatt.votes.dto.CreateVoteRequest; import com.vomatt.votes.dto.InviteeResponse; import com.vomatt.votes.dto.InviteesRequest;
import com.vomatt.votes.dto.VoteRequest;
import com.vomatt.votes.dto.VoteResponse;

/**
 * Ballot changes racing Close and Edit, against real Postgres with real commits. One side holds its transaction
 * open before commit; the other must either wait for it or see its result, never act on a stale Poll.
 */
@Import(VoteServiceSlice.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Poll-row locking (Postgres)")
class PollLockPostgresTest extends PostgresRepositoryTest {

    private static final long HOLD_MS = 500;

    @Autowired VoteService voteService;
    @Autowired VoteOptionRepository voteOptionRepository;
    @Autowired TransactionTemplate tx;

    private final List<UUID> createdUsers = new ArrayList<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private User owner;
    private User voter;
    private Vote poll;
    private UUID optionA;

    @BeforeEach
    void setUp() {
        tx.executeWithoutResult(s -> {
            owner = track(persistUser("owner-" + UUID.randomUUID()));
            voter = track(persistUser("voter-" + UUID.randomUUID()));
        });
    }

    @AfterEach
    void cleanUp() {
        pool.shutdownNow();
        // polls, options and ballots cascade with their users
        tx.executeWithoutResult(s -> em.createNativeQuery("DELETE FROM vomatt.users WHERE id IN (:ids)")
                .setParameter("ids", createdUsers).executeUpdate());
    }

    private User track(User user) {
        createdUsers.add(user.getId());
        return user;
    }

    private void createPoll(OffsetDateTime start, OffsetDateTime end) {
        tx.executeWithoutResult(s -> {
            poll = persistPoll(owner, start, end, "A", "B");
            poll.setAudience(Audience.UNLISTED); // a Public Poll cannot be Closed
        });
        optionA = voteOptionRepository.findByVoteIdOrderByDisplayOrder(poll.getId()).getFirst().getId();
    }

    private VoteResponse cast() {
        VoteRequest request = new VoteRequest();
        request.setOptionIds(List.of(optionA.toString()));
        return voteService.vote(poll.getId().toString(), request, voter.getId().toString(), "127.0.0.1");
    }

    private VoteResponse close() {
        return voteService.closeVote(poll.getId().toString(), owner.getId().toString());
    }

    // Runs action in an outer transaction that stays open until release; acted fires once the action is done
    private <T> Future<T> holding(Callable<T> action, CountDownLatch acted, CountDownLatch release) {
        return pool.submit(() -> tx.execute(s -> {
            try {
                T result = action.call();
                acted.countDown();
                release.await(10, TimeUnit.SECONDS);
                return result;
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }));
    }

    // true when the call is still blocked after HOLD_MS
    private static boolean blocked(Future<?> call) throws Exception {
        try {
            call.get(HOLD_MS, TimeUnit.MILLISECONDS);
            return false;
        } catch (TimeoutException e) {
            return true;
        } catch (ExecutionException e) {
            return false;
        }
    }

    // option id -> stored vote_count
    private Map<UUID, Long> counts() {
        Map<UUID, Long> result = new HashMap<>();
        for (Object row : em.createNativeQuery("SELECT id, vote_count FROM vomatt.vote_options WHERE vote_id = :voteId")
                .setParameter("voteId", poll.getId()).getResultList()) {
            Object[] r = (Object[]) row;
            result.put((UUID) r[0], ((Number) r[1]).longValue());
        }
        return result;
    }

    private long ballots() {
        return ((Number) em.createNativeQuery("SELECT COUNT(*) FROM vomatt.user_votes WHERE vote_id = :voteId")
                .setParameter("voteId", poll.getId()).getSingleResult()).longValue();
    }

    private OffsetDateTime storedEndTime() {
        return em.createQuery("SELECT v.endTime FROM Vote v WHERE v.id = :id", OffsetDateTime.class)
                .setParameter("id", poll.getId()).getSingleResult();
    }

    private static void assertApiError(Future<?> call, MessageKey key) {
        assertThatThrownBy(() -> call.get(10, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause().isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getMessageKey()).isEqualTo(key));
    }

    private Void delete() {
        voteService.deleteVote(poll.getId().toString(), owner.getId().toString());
        return null;
    }

    @Test
    @DisplayName("should make email binding wait for the owner inviting the same user by ID, then drop the email row instead of failing")
    void shouldBindAfterOwnerInvitesSameUserWhenOwnerHoldsPollFirst() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        String email = "lock-" + UUID.randomUUID() + "@example.com";
        tx.executeWithoutResult(s -> {
            poll = persistPoll(owner, now.minusMinutes(1), now.plusDays(1), "A", "B");
            poll.setAudience(Audience.INVITE_ONLY);
            em.persist(new Invitee(poll, email));
            voter.setEmail(email);
            em.merge(voter);
        });
        InviteesRequest byId = new InviteesRequest();
        byId.setUserIds(List.of(voter.getId()));
        CountDownLatch invited = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<List<InviteeResponse>> inviter = holding(
                () -> voteService.addInvitees(poll.getId().toString(), byId, owner.getId().toString()), invited, release);
        assertThat(invited.await(10, TimeUnit.SECONDS)).isTrue();

        Future<Void> binder = pool.submit(() -> tx.execute(s -> {
            voteService.bindEmailInvitees(voter);
            return null;
        }));
        assertThat(blocked(binder)).as("Binding waits for the owner's invite").isTrue();
        release.countDown();
        inviter.get(10, TimeUnit.SECONDS);
        binder.get(10, TimeUnit.SECONDS);

        assertThat(em.createQuery("SELECT i FROM Invitee i WHERE i.vote.id = :id", Invitee.class)
                .setParameter("id", poll.getId()).getResultList()).singleElement()
                .satisfies(i -> assertThat(i.getEmail()).isNull());
    }

    @Test
    @DisplayName("should give up with a lock failure (409 via GlobalExceptionHandler) when the Poll-row lock stays held past the lock timeout")
    void shouldRejectCastWithConflictWhenLockHeldPastTimeout() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        createPoll(now.minusMinutes(1), now.plusDays(1));
        CountDownLatch closed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<VoteResponse> closer = holding(this::close, closed, release);
        assertThat(closed.await(10, TimeUnit.SECONDS)).isTrue();

        Future<VoteResponse> caster = pool.submit(this::cast);
        try {
            // GlobalExceptionHandler maps it to 409 common.conflict
            assertThatThrownBy(() -> caster.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class).cause().isInstanceOf(PessimisticLockingFailureException.class);
        } finally {
            release.countDown();
        }
        closer.get(10, TimeUnit.SECONDS);
        assertThat(ballots()).isZero();
    }

    @Test
    @DisplayName("should make Delete wait for an in-flight Cast and then delete the Poll")
    void shouldDeleteAfterCastWhenCastHoldsPollFirst() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        createPoll(now.minusMinutes(1), now.plusDays(1));
        CountDownLatch cast = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<VoteResponse> caster = holding(this::cast, cast, release);
        assertThat(cast.await(10, TimeUnit.SECONDS)).isTrue();

        Future<Void> deleter = pool.submit(this::delete);
        assertThat(blocked(deleter)).as("Delete waits for the Cast").isTrue();
        release.countDown();
        caster.get(10, TimeUnit.SECONDS);
        deleter.get(10, TimeUnit.SECONDS);

        assertThatThrownBy(this::cast).isInstanceOfSatisfying(ApiException.class,
                ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.VOTE_NOT_FOUND));
    }

    @Test
    @DisplayName("should reject a Cast with vote.not_found when Delete holds the Poll first")
    void shouldRejectCastWhenDeleteCommitsFirst() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        createPoll(now.minusMinutes(1), now.plusDays(1));
        CountDownLatch deleted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<Void> deleter = holding(this::delete, deleted, release);
        assertThat(deleted.await(10, TimeUnit.SECONDS)).isTrue();

        Future<VoteResponse> caster = pool.submit(this::cast);
        assertThat(blocked(caster)).as("Cast waits for the Delete").isTrue();
        release.countDown();
        deleter.get(10, TimeUnit.SECONDS);

        assertApiError(caster, MessageKey.VOTE_NOT_FOUND);
        assertThat(ballots()).isZero();
    }

    @Test
    @DisplayName("should make Close wait for an in-flight Cast and count its Ballot when the Cast passed its check first")
    void shouldCountBallotWhenCastCommitsBeforeClose() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        createPoll(now.minusHours(1), now.plusDays(1));
        CountDownLatch cast = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<VoteResponse> caster = holding(this::cast, cast, release);
        assertThat(cast.await(10, TimeUnit.SECONDS)).isTrue();

        Future<VoteResponse> closer = pool.submit(this::close);
        assertThat(blocked(closer)).as("Close waits for the Cast").isTrue();
        release.countDown();
        caster.get(10, TimeUnit.SECONDS);
        closer.get(10, TimeUnit.SECONDS);

        assertThat(counts().get(optionA)).isEqualTo(1);
        assertThat(ballots()).isEqualTo(1);
    }

    @Test
    @DisplayName("should reject a Cast with vote.ended when Close holds the Poll first")
    void shouldRejectCastWhenCloseCommitsFirst() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        createPoll(now.minusHours(1), now.plusDays(1));
        CountDownLatch closed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<VoteResponse> closer = holding(this::close, closed, release);
        assertThat(closed.await(10, TimeUnit.SECONDS)).isTrue();

        Future<VoteResponse> caster = pool.submit(this::cast);
        assertThat(blocked(caster)).as("Cast waits for the Close").isTrue();
        release.countDown();
        closer.get(10, TimeUnit.SECONDS);

        assertApiError(caster, MessageKey.VOTE_ENDED);
        assertThat(ballots()).isZero();
        assertThat(counts().values()).containsOnly(0L);
    }

    @Test
    @DisplayName("should make Close wait for an in-flight Retraction and apply it before the Poll Ended")
    void shouldApplyRetractionWhenRetractCommitsBeforeClose() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        createPoll(now.minusHours(1), now.plusDays(1));
        cast();
        CountDownLatch retracted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<VoteResponse> retracter = holding(
                () -> voteService.retract(poll.getId().toString(), voter.getId().toString()), retracted, release);
        assertThat(retracted.await(10, TimeUnit.SECONDS)).isTrue();

        Future<VoteResponse> closer = pool.submit(this::close);
        assertThat(blocked(closer)).as("Close waits for the Retraction").isTrue();
        release.countDown();
        retracter.get(10, TimeUnit.SECONDS);
        closer.get(10, TimeUnit.SECONDS);

        assertThat(counts().get(optionA)).isZero();
        assertThat(ballots()).isZero();
    }

    @Test
    @DisplayName("should reject a Ballot at the opening moment cleanly when an in-flight Edit replaced the options")
    void shouldRejectBallotWhenEditReplacedOptionsAtOpening() throws Exception {
        // wide margin so the Edit always takes its lock while the Poll is still Scheduled, even on a slow runner
        OffsetDateTime start = OffsetDateTime.now().plusSeconds(3);
        createPoll(start, start.plusDays(1));
        CreateVoteRequest edit = new CreateVoteRequest();
        edit.setTitle("edited");
        edit.setEndTime(start.plusDays(1));
        edit.setOptions(List.of(option("X"), option("Y")));
        CountDownLatch edited = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<VoteResponse> editor = holding(
                () -> voteService.updateVote(poll.getId().toString(), edit, owner.getId().toString()), edited, release);
        assertThat(edited.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(OffsetDateTime.now()).as("Edit held the lock before the Poll opened").isBefore(start);

        // the Poll opens while the Edit is still uncommitted; the first Ballot targets an old option
        Thread.sleep(Math.max(0, Duration.between(OffsetDateTime.now(), start).toMillis() + 50));
        Future<VoteResponse> caster = pool.submit(this::cast);
        assertThat(blocked(caster)).as("Cast waits for the Edit").isTrue();
        release.countDown();
        editor.get(10, TimeUnit.SECONDS);

        assertApiError(caster, MessageKey.VOTE_OPTION_NOT_FOUND);
        assertThat(ballots()).isZero();
    }

    @Test
    @DisplayName("should reject an Edit with vote.not_editable when a first Ballot holds the Poll at the opening moment")
    void shouldRejectEditWhenBallotHoldsPollAtOpening() throws Exception {
        OffsetDateTime start = OffsetDateTime.now().plus(Duration.ofMillis(500));
        createPoll(start, start.plusDays(1));
        CreateVoteRequest edit = new CreateVoteRequest();
        edit.setTitle("edited");
        edit.setEndTime(start.plusDays(1));
        edit.setOptions(List.of(option("X"), option("Y")));
        Thread.sleep(Math.max(0, Duration.between(OffsetDateTime.now(), start).toMillis() + 50));
        CountDownLatch cast = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<VoteResponse> caster = holding(this::cast, cast, release);
        assertThat(cast.await(10, TimeUnit.SECONDS)).isTrue();

        Future<VoteResponse> editor = pool.submit(
                () -> voteService.updateVote(poll.getId().toString(), edit, owner.getId().toString()));
        assertThat(blocked(editor)).as("Edit waits for the Ballot").isTrue();
        release.countDown();
        caster.get(10, TimeUnit.SECONDS);

        assertApiError(editor, MessageKey.VOTE_NOT_EDITABLE);
        assertThat(ballots()).isEqualTo(1);
        assertThat(counts().get(optionA)).isEqualTo(1);
    }

    // ─── Invite-only auto-end ───────────────────────────────────────────────

    // An Open Invite-only Poll whose Invitees are the given users
    private void createInviteOnlyPoll(User... invitees) {
        OffsetDateTime now = OffsetDateTime.now();
        tx.executeWithoutResult(s -> {
            poll = persistPoll(owner, now.minusHours(1), now.plusDays(1), "A", "B");
            poll.setAudience(Audience.INVITE_ONLY);
            for (User invitee : invitees) {
                em.persist(new Invitee(poll, invitee));
            }
        });
        optionA = voteOptionRepository.findByVoteIdOrderByDisplayOrder(poll.getId()).getFirst().getId();
    }

    private VoteResponse castAs(User user) {
        VoteRequest request = new VoteRequest();
        request.setOptionIds(List.of(optionA.toString()));
        return voteService.vote(poll.getId().toString(), request, user.getId().toString(), "127.0.0.1");
    }

    private static boolean unsealed(VoteResponse response) {
        return response.getOptions().stream().allMatch(o -> o.getVotes() != null);
    }

    @Test
    @DisplayName("should reject a Ballot with vote.ended when the last Invitee's Ballot holds the Poll first and ends it")
    void shouldRejectBallotWhenLastInviteeBallotEndsPollFirst() throws Exception {
        User last = track(tx.execute(s -> persistUser("last-" + UUID.randomUUID())));
        createInviteOnlyPoll(voter, last);
        castAs(voter);
        CountDownLatch cast = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<VoteResponse> ender = holding(() -> castAs(last), cast, release);
        assertThat(cast.await(10, TimeUnit.SECONDS)).isTrue();

        Future<VoteResponse> ownerCast = pool.submit(() -> castAs(owner));
        assertThat(blocked(ownerCast)).as("owner's Ballot waits for the ending Ballot").isTrue();
        release.countDown();
        OffsetDateTime endedAt = ender.get(10, TimeUnit.SECONDS).getEndTime();

        assertApiError(ownerCast, MessageKey.VOTE_ENDED);
        assertThatThrownBy(() -> castAs(voter)).isInstanceOfSatisfying(ApiException.class,
                ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.VOTE_ENDED));
        assertThat(ballots()).isEqualTo(2);
        assertThat(storedEndTime().toInstant()).isEqualTo(endedAt.toInstant().truncatedTo(ChronoUnit.MICROS));
    }

    @Test
    @DisplayName("should persist the auto-end together with Turnout and option counts when the last Invitee's Ballot lands")
    void shouldPersistEndAndCountsWhenLastInviteeBallotEndsPoll() {
        User last = track(tx.execute(s -> persistUser("last-" + UUID.randomUUID())));
        createInviteOnlyPoll(voter, last);
        UUID optionB = counts().keySet().stream().filter(id -> !id.equals(optionA)).findFirst().orElseThrow();
        castAs(voter);
        VoteRequest request = new VoteRequest();
        request.setOptionIds(List.of(optionB.toString()));

        VoteResponse ended = voteService.vote(poll.getId().toString(), request, last.getId().toString(), "127.0.0.1");

        // The end is set on the locked Poll before the count updates clear the persistence context
        assertThat(unsealed(ended)).isTrue();
        assertThat(storedEndTime().toInstant()).isEqualTo(ended.getEndTime().toInstant().truncatedTo(ChronoUnit.MICROS));
        assertThat(storedEndTime()).as("ended now, not at the Deadline a day away").isBefore(OffsetDateTime.now().plusMinutes(1));
        assertThat(ballots()).isEqualTo(2);
        assertThat(counts()).containsEntry(optionA, 1L).containsEntry(optionB, 1L);
        assertThat(((Number) em.createNativeQuery("SELECT turnout FROM vomatt.votes WHERE id = :id")
                .setParameter("id", poll.getId()).getSingleResult()).longValue()).isEqualTo(2);
    }

    @Test
    @DisplayName("should end the Poll exactly once and let no Ballot land after it when the last Ballots race")
    void shouldEndOnceWhenLastInviteeBallotsRace() throws Exception {
        User second = track(tx.execute(s -> persistUser("second-" + UUID.randomUUID())));
        User third = track(tx.execute(s -> persistUser("third-" + UUID.randomUUID())));
        createInviteOnlyPoll(voter, second, third);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<VoteResponse>> casts = new ArrayList<>();
        for (User user : List.of(voter, second, third, owner)) {
            casts.add(pool.submit(() -> {
                go.await(10, TimeUnit.SECONDS);
                return castAs(user);
            }));
        }
        go.countDown();

        List<VoteResponse> landed = new ArrayList<>();
        for (int i = 0; i < casts.size(); i++) {
            try {
                landed.add(casts.get(i).get(10, TimeUnit.SECONDS));
            } catch (ExecutionException e) {
                // only the owner (last) can come too late: the Poll Ended once all three Invitees held a Ballot
                assertThat(i).as("only the owner's Ballot can be refused").isEqualTo(3);
                assertThat(e.getCause()).isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.VOTE_ENDED));
            }
        }

        // exactly one Ballot (the last Invitee's) saw the Poll End
        List<VoteResponse> ending = landed.stream().filter(PollLockPostgresTest::unsealed).toList();
        assertThat(ending).hasSize(1);
        assertThat(storedEndTime().toInstant())
                .isEqualTo(ending.getFirst().getEndTime().toInstant().truncatedTo(ChronoUnit.MICROS));
        assertThat(ballots()).isEqualTo(landed.size());
        assertThatThrownBy(() -> castAs(owner)).isInstanceOfSatisfying(ApiException.class,
                ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.VOTE_ENDED));
    }

    @Test
    @DisplayName("should keep the first end time when two Closes run concurrently")
    void shouldKeepFirstEndTimeWhenClosedConcurrently() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        createPoll(now.minusHours(1), now.plusDays(1));
        CountDownLatch closed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<VoteResponse> first = holding(this::close, closed, release);
        assertThat(closed.await(10, TimeUnit.SECONDS)).isTrue();

        Future<VoteResponse> second = pool.submit(this::close);
        assertThat(blocked(second)).as("second Close waits for the first").isTrue();
        release.countDown();
        OffsetDateTime firstEnd = first.get(10, TimeUnit.SECONDS).getEndTime();
        second.get(10, TimeUnit.SECONDS);

        assertThat(storedEndTime().toInstant()).isEqualTo(firstEnd.toInstant().truncatedTo(ChronoUnit.MICROS));
    }

    private static CreateVoteRequest.VoteOptionRequest option(String text) {
        CreateVoteRequest.VoteOptionRequest option = new CreateVoteRequest.VoteOptionRequest();
        option.setText(text);
        return option;
    }
}
