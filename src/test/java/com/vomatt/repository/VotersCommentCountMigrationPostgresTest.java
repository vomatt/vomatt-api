package com.vomatt.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import com.vomatt.entity.User;
import com.vomatt.entity.Vote;
import com.vomatt.entity.VoteComment;

/** Runs the real db/migration/V20261116120500__voters_comment_count.sql again inside the rolled-back test transaction. */
@DisplayName("V20261116120500__voters_comment_count.sql (Postgres)")
class VotersCommentCountMigrationPostgresTest extends PostgresRepositoryTest {

    private static final String SCRIPT = "db/migration/V20261116120500__voters_comment_count.sql";

    @Test
    void shouldBackfillLiveCommentsAndRepliesWhenMigrationReruns() throws Exception {
        User user = persistUser("cc-" + UUID.randomUUID());
        OffsetDateTime now = OffsetDateTime.now();
        Vote commented = persistPoll(user, now.minusHours(1), now.plusDays(1), "A", "B");
        VoteComment root = new VoteComment(commented, user, "root");
        em.persist(root);
        VoteComment reply = new VoteComment(commented, user, "reply");
        reply.setParent(root);
        em.persist(reply);
        VoteComment gone = new VoteComment(commented, user, "gone");
        gone.softDelete();
        em.persist(gone);
        em.flush();
        em.createNativeQuery("UPDATE vomatt.votes SET comment_count = 0 WHERE id = :id")
                .setParameter("id", commented.getId()).executeUpdate();

        String script = new ClassPathResource(SCRIPT).getContentAsString(StandardCharsets.UTF_8);
        for (int run = 0; run < 2; run++) {
            em.createNativeQuery(script).executeUpdate();
        }

        // Comments and Replies count, deleted ones do not
        assertThat(commentCount(commented)).isEqualTo(2);
    }

    private int commentCount(Vote poll) {
        return ((Number) em.createNativeQuery("SELECT comment_count FROM vomatt.votes WHERE id = :id")
                .setParameter("id", poll.getId()).getSingleResult()).intValue();
    }
}
