package com.vomatt.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import com.vomatt.entity.CommentLike;
import com.vomatt.entity.User;
import com.vomatt.entity.Vote;
import com.vomatt.entity.VoteComment;

/** Runs the real db/migration/V10__mark_existing_likes_read.sql again inside the rolled-back test transaction. */
@DisplayName("V10__mark_existing_likes_read.sql (Postgres)")
class MarkExistingLikesReadMigrationPostgresTest extends PostgresRepositoryTest {

    private static final String SCRIPT = "db/migration/V10__mark_existing_likes_read.sql";

    @Test
    @DisplayName("should mark every like by someone else on a Comment read for its author, not self-likes, also on rerun")
    void shouldMarkLikesReadWhenMigrationRuns() throws Exception {
        User author = persistUser("mig-author-" + System.nanoTime());
        User liker = persistUser("mig-liker-" + System.nanoTime());
        Vote poll = persistPoll(author, OffsetDateTime.now().minusHours(1), OffsetDateTime.now().plusDays(1), "A", "B");
        VoteComment comment = new VoteComment(poll, author, "hello");
        em.persist(comment);
        CommentLike like = new CommentLike(comment, liker);
        em.persist(like);
        em.persist(new CommentLike(comment, author));
        em.flush();

        String script = new ClassPathResource(SCRIPT).getContentAsString(StandardCharsets.UTF_8);
        for (int run = 0; run < 2; run++) {
            em.createNativeQuery(script).executeUpdate();
        }

        assertThat(reads()).containsExactly(author.getId() + " LIKE " + like.getId());
    }

    @SuppressWarnings("unchecked")
    private List<String> reads() {
        return em.createNativeQuery("SELECT CAST(user_id AS text) || ' ' || kind || ' ' || CAST(source_id AS text) "
                + "FROM vomatt.notification_reads WHERE kind = 'LIKE'").getResultList();
    }
}
