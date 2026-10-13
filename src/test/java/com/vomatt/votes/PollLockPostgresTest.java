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
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.entity.User;
import com.vomatt.entity.Vote;
import com.vomatt.repository.PostgresRepositoryTest;
import com.vomatt.repository.VoteOptionRepository;
import com.vomatt.votes.dto.CreateVoteRequest;
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
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
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
        tx.executeWithoutResult(s -> poll = persistPoll(owner, start, end, "A", "B"));
        optionA = voteOptionRepository.findByVoteIdOrderByDisplayOrder(poll.getId()).getFirst().getId();
    }

    private VoteResponse cast() {
        VoteRequest request = new VoteRequest();
        request.setOptionIds(List.of(optionA.toString()));
        return voteService.vote(poll.getId().toString(), request, voter.getId().toString(), "127.0.0.1");
    }

    private VoteResponse close() {
        return voteService.deactivateVote(poll.getId().toString(), owner.getId().toString());
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
        boolean closeWaited = blocked(closer);
        Map<UUID, Long> snapshot = closeWaited ? null : counts();
        release.countDown();

        if (closeWaited) {
            caster.get(10, TimeUnit.SECONDS);
            closer.get(10, TimeUnit.SECONDS);
            assertThat(counts().get(optionA)).isEqualTo(1);
            assertThat(ballots()).isEqualTo(1);
        } else {
            // Close returned first: the Cast must not change the frozen counts
            assertApiError(caster, MessageKey.VOTE_ENDED);
            assertThat(counts()).isEqualTo(snapshot);
        }
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
        boolean closeWaited = blocked(closer);
        Map<UUID, Long> snapshot = closeWaited ? null : counts();
        release.countDown();

        if (closeWaited) {
            retracter.get(10, TimeUnit.SECONDS);
            closer.get(10, TimeUnit.SECONDS);
            assertThat(counts().get(optionA)).isZero();
            assertThat(ballots()).isZero();
        } else {
            assertApiError(retracter, MessageKey.VOTE_ENDED);
            assertThat(counts()).isEqualTo(snapshot);
        }
    }

    @Test
    @DisplayName("should reject a Ballot at the opening moment cleanly when an in-flight Edit replaced the options")
    void shouldRejectBallotWhenEditReplacedOptionsAtOpening() throws Exception {
        OffsetDateTime start = OffsetDateTime.now().plusSeconds(1);
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
