package com.vomatt.comments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
import com.vomatt.entity.VoteComment;
import com.vomatt.repository.PostgresRepositoryTest;
import com.vomatt.votes.VoteServiceSlice;

/** Likes racing each other, against real Postgres with real commits. */
@Import({ VoteCommentService.class, CommentMapper.class, VoteServiceSlice.class })
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Comment likes (Postgres)")
class CommentLikePostgresTest extends PostgresRepositoryTest {

    @Autowired VoteCommentService commentService;
    @Autowired TransactionTemplate tx;

    private final List<UUID> createdUsers = new ArrayList<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private User liker;
    private VoteComment comment;

    @BeforeEach
    void setUp() {
        tx.executeWithoutResult(s -> {
            User author = persistUser("cl-author-" + UUID.randomUUID());
            liker = persistUser("cl-liker-" + UUID.randomUUID());
            createdUsers.add(author.getId());
            createdUsers.add(liker.getId());
            OffsetDateTime now = OffsetDateTime.now();
            Vote poll = persistPoll(author, now.minusHours(1), now.plusDays(1), "A", "B");
            comment = new VoteComment(poll, author, "hi");
            em.persist(comment);
        });
    }

    @AfterEach
    void cleanUp() {
        pool.shutdownNow();
        // polls, comments and likes cascade with their users
        tx.executeWithoutResult(s -> em.createNativeQuery("DELETE FROM vomatt.users WHERE id IN (:ids)")
                .setParameter("ids", createdUsers).executeUpdate());
    }

    private void like() {
        commentService.likeComment(comment.getId(), liker.getId().toString());
        // the old exists-then-save deferred its INSERT to commit; send it now so the row lock is taken here
        em.flush();
    }

    // true once a session of this test database is blocked on a lock while running a statement that starts with
    // the prefix (written with LIKE wildcards escaped)
    private boolean waitsOnLock(String likePrefix) {
        return ((Number) em.createNativeQuery("""
                SELECT COUNT(*) FROM pg_stat_activity
                WHERE datname = current_database() AND wait_event_type = 'Lock' AND query ILIKE :prefix || '%'""")
                .setParameter("prefix", likePrefix).getSingleResult()).longValue() > 0;
    }

    private boolean likeWaitsOnLock() {
        return waitsOnLock("insert into vomatt.comment\\_likes");
    }

    @Test
    @DisplayName("should succeed for both and keep one like when the same user likes twice at the same moment")
    void shouldKeepOneLikeWhenSameUserLikesConcurrently() throws Exception {
        CountDownLatch liked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> first = pool.submit(() -> tx.executeWithoutResult(s -> {
            like();
            liked.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(liked.await(10, TimeUnit.SECONDS)).isTrue();

        Future<?> second = pool.submit(() -> tx.executeWithoutResult(s -> like()));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!likeWaitsOnLock() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(likeWaitsOnLock()).as("second like waits on the first one's uncommitted row").isTrue();
        release.countDown();
        first.get(10, TimeUnit.SECONDS);
        second.get(10, TimeUnit.SECONDS);

        assertThat(((Number) em.createNativeQuery("SELECT COUNT(*) FROM vomatt.comment_likes WHERE comment_id = :id")
                .setParameter("id", comment.getId()).getSingleResult()).longValue()).isEqualTo(1);
    }

    private void unlike() {
        commentService.unlikeComment(comment.getId(), liker.getId().toString());
        em.flush();
    }

    @Test
    @DisplayName("should succeed for both and remove the like when the same user unlikes twice at the same moment")
    void shouldRemoveLikeWhenSameUserUnlikesConcurrently() throws Exception {
        tx.executeWithoutResult(s -> like());
        CountDownLatch unliked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> first = pool.submit(() -> tx.executeWithoutResult(s -> {
            unlike();
            unliked.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(unliked.await(10, TimeUnit.SECONDS)).isTrue();

        Future<?> second = pool.submit(() -> tx.executeWithoutResult(s -> unlike()));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!waitsOnLock("delete from vomatt.comment\\_likes") && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(waitsOnLock("delete from vomatt.comment\\_likes"))
                .as("second unlike waits on the first one's uncommitted delete").isTrue();
        release.countDown();
        first.get(10, TimeUnit.SECONDS);
        second.get(10, TimeUnit.SECONDS);

        assertThat(((Number) em.createNativeQuery("SELECT COUNT(*) FROM vomatt.comment_likes WHERE comment_id = :id")
                .setParameter("id", comment.getId()).getSingleResult()).longValue()).isZero();
    }

    @Test
    @DisplayName("should return 404 user.not_found when the signed-in user no longer exists")
    void shouldThrowUserNotFoundWhenLikerIsGone() {
        String gone = UUID.randomUUID().toString();

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> commentService.likeComment(comment.getId(), gone)))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.USER_NOT_FOUND));
    }
}
