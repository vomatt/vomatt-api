package com.vomatt.comments;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
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
        em.flush();
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
        try {
            second.get(500, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // waiting on the first like's uncommitted row, as intended
        }
        release.countDown();
        first.get(10, TimeUnit.SECONDS);
        second.get(10, TimeUnit.SECONDS);

        assertThat(((Number) em.createNativeQuery("SELECT COUNT(*) FROM vomatt.comment_likes WHERE comment_id = :id")
                .setParameter("id", comment.getId()).getSingleResult()).longValue()).isEqualTo(1);
    }
}
