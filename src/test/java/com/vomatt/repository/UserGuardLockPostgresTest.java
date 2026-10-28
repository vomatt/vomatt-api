package com.vomatt.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.vomatt.common.util.UUIDv7Generator;
import com.vomatt.entity.Tag;
import com.vomatt.entity.User;
import com.vomatt.entity.UserVote;
import com.vomatt.entity.Vote;
import com.vomatt.entity.VoteComment;

/**
 * Guarded inserts racing an account deletion, with real commits: the users guard key-share-locks the row, so each
 * insert waits for the deletion and then inserts nothing, rather than passing the guard and failing the foreign key.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UserGuardLockPostgresTest extends PostgresRepositoryTest {

    @Autowired TransactionTemplate tx;
    @Autowired VoteBookmarkRepository bookmarkRepository;
    @Autowired VoteReportRepository voteReportRepository;
    @Autowired TagRepository tagRepository;
    @Autowired CommentLikeRepository likeRepository;
    @Autowired CommentReportRepository commentReportRepository;
    @Autowired UserRepository userRepository;
    @Autowired VoteRepository voteRepository;

    private final ExecutorService pool = Executors.newFixedThreadPool(6);
    private User owner;
    private User me;
    private Vote poll;
    private VoteComment comment;
    private Tag tag;

    @BeforeEach
    void setUp() {
        tx.executeWithoutResult(s -> {
            owner = persistUser("ug-owner-" + UUID.randomUUID());
            me = persistUser("ug-me-" + UUID.randomUUID());
            OffsetDateTime now = OffsetDateTime.now();
            poll = persistPoll(owner, now.minusHours(1), now.plusDays(1), "A", "B");
            comment = new VoteComment(poll, owner, "hi");
            em.persist(comment);
            tag = persistTag();
        });
    }

    @AfterEach
    void cleanUp() {
        pool.shutdownNow();
        tx.executeWithoutResult(s -> {
            em.createNativeQuery("DELETE FROM vomatt.users WHERE id IN (:ids)")
                    .setParameter("ids", List.of(owner.getId(), me.getId())).executeUpdate();
            em.createNativeQuery("DELETE FROM vomatt.tags WHERE id = :id").setParameter("id", tag.getId()).executeUpdate();
        });
    }

    @Test
    void shouldInsertNothingWhenUserDeletionCommitsWhileInsertWaits() throws Exception {
        CountDownLatch deleted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> deletion = pool.submit(() -> tx.executeWithoutResult(s -> {
            em.createNativeQuery("DELETE FROM vomatt.users WHERE id = :id").setParameter("id", me.getId()).executeUpdate();
            deleted.countDown();
            await(release);
        }));
        assertThat(deleted.await(5, TimeUnit.SECONDS)).isTrue();

        UUID userId = me.getId();
        List<Future<Integer>> inserts = List.of(
                insert(() -> bookmarkRepository.insertIfAbsent(UUIDv7Generator.generateId(), userId, poll.getId())),
                insert(() -> voteReportRepository.insertIfAbsent(UUIDv7Generator.generateId(), poll.getId(), userId,
                        "SPAM", null)),
                insert(() -> tagRepository.follow(userId, tag.getId())),
                insert(() -> likeRepository.insertIfAbsent(UUIDv7Generator.generateId(), comment.getId(), userId)),
                insert(() -> commentReportRepository.insertIfAbsent(UUIDv7Generator.generateId(), comment.getId(),
                        userId, "SPAM", null)));
        Thread.sleep(500);
        assertThat(inserts).noneMatch(Future::isDone);
        release.countDown();
        deletion.get(5, TimeUnit.SECONDS);

        for (Future<Integer> insert : inserts) {
            assertThat(insert.get(5, TimeUnit.SECONDS)).isZero();
        }
    }

    @Test
    void shouldNotDeadlockWhenUserReportsBallotedPollDuringOwnAccountDeletion() throws Exception {
        tx.executeWithoutResult(s -> em.persist(new UserVote(em.find(User.class, me.getId()),
                em.find(Vote.class, poll.getId()), poll.getOptions().iterator().next())));
        UUID userId = me.getId();
        // As UserService.deleteUser: the user row first, then the Poll rows of their Ballots, then the delete
        CountDownLatch pollLocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> deletion = pool.submit(() -> tx.executeWithoutResult(s -> {
            userRepository.lockForDelete(userId);
            voteRepository.decrementTurnoutForUserBallots(userId);
            pollLocked.countDown();
            await(release);
            em.createNativeQuery("DELETE FROM vomatt.users WHERE id = :id").setParameter("id", userId).executeUpdate();
        }));
        assertThat(pollLocked.await(5, TimeUnit.SECONDS)).isTrue();

        // Before the user-row lock the report took users then waited on the Poll that deletion held: a cycle
        Future<Integer> report = insert(() -> voteReportRepository.insertIfAbsent(UUIDv7Generator.generateId(),
                poll.getId(), userId, "SPAM", null));
        Thread.sleep(300);
        assertThat(report.isDone()).isFalse();
        release.countDown();

        deletion.get(5, TimeUnit.SECONDS);
        assertThat(report.get(5, TimeUnit.SECONDS)).isZero();
    }

    @Test
    void shouldNotDeadlockWhenUserBookmarksOwnPollDuringOwnAccountDeletion() throws Exception {
        Vote own = tx.execute(s -> persistPoll(em.find(User.class, me.getId()), OffsetDateTime.now().minusHours(1),
                OffsetDateTime.now().plusDays(1), "A", "B"));
        UUID userId = me.getId();
        CountDownLatch userLocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> deletion = pool.submit(() -> tx.executeWithoutResult(s -> {
            userRepository.lockForDelete(userId);
            userLocked.countDown();
            await(release);
            // Cascades to the user's own Poll, after the user row
            em.createNativeQuery("DELETE FROM vomatt.users WHERE id = :id").setParameter("id", userId).executeUpdate();
        }));
        assertThat(userLocked.await(5, TimeUnit.SECONDS)).isTrue();

        Future<Integer> bookmark = insert(() -> bookmarkRepository.insertIfAbsent(UUIDv7Generator.generateId(), userId,
                own.getId()));
        Thread.sleep(300);
        assertThat(bookmark.isDone()).isFalse();
        release.countDown();

        deletion.get(5, TimeUnit.SECONDS);
        assertThat(bookmark.get(5, TimeUnit.SECONDS)).isZero();
    }

    @Test
    void shouldNotBookmarkDeletedPollAndNotCountOldBookmarkAsRepeat() {
        UUID userId = me.getId();
        tx.executeWithoutResult(s -> bookmarkRepository.insertIfAbsent(UUIDv7Generator.generateId(), userId,
                poll.getId()));
        tx.executeWithoutResult(s -> em.createNativeQuery("UPDATE vomatt.votes SET deleted_at = now() WHERE id = :id")
                .setParameter("id", poll.getId()).executeUpdate());

        Integer inserted = tx.execute(s -> bookmarkRepository.insertIfAbsent(UUIDv7Generator.generateId(),
                owner.getId(), poll.getId()));
        assertThat(inserted).isZero();
        assertThat(bookmarkRepository.existsByUserIdAndVoteIdAndVoteDeletedAtIsNull(userId, poll.getId())).isFalse();
    }

    private Future<Integer> insert(Supplier<Integer> insert) {
        return pool.submit(() -> tx.execute(s -> insert.get()));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
