package com.vomatt.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import com.vomatt.entity.User;
import com.vomatt.entity.Vote;
import com.vomatt.entity.VoterVisibility;

/** Runs the real db/migrations/2026-10-18-legacy-anonymous-poll.sql inside the rolled-back test transaction. */
@DisplayName("2026-10-18-legacy-anonymous-poll.sql (Postgres)")
class LegacyAnonymousPollMigrationPostgresTest extends PostgresRepositoryTest {

    private static final String SCRIPT = "db/migrations/2026-10-18-legacy-anonymous-poll.sql";

    @Test
    @DisplayName("should turn legacy anonymous OWNER Polls into NOBODY and sync is_anonymous to NOBODY")
    void shouldBackfillNobodyWhenLegacyAnonymousPollKeptOwner() throws Exception {
        User owner = persistUser("legacy-anon-" + System.nanoTime());
        Vote legacy = persistPoll(owner, true, VoterVisibility.OWNER);
        Vote signedIn = persistPoll(owner, true, VoterVisibility.SIGNED_IN);
        Vote nobody = persistPoll(owner, false, VoterVisibility.NOBODY);
        Vote plain = persistPoll(owner, false, VoterVisibility.OWNER);
        em.flush();

        // Comments are whole lines and no literal contains ';'
        String script = new ClassPathResource(SCRIPT).getContentAsString(StandardCharsets.UTF_8).lines()
                .filter(line -> !line.startsWith("--")).collect(Collectors.joining("\n"));
        List<String> statements = Arrays.stream(script.split(";")).map(String::strip).filter(sql -> !sql.isEmpty()).toList();
        assertThat(statements).hasSize(2);
        // Run twice: the migration must be re-runnable
        for (int run = 0; run < 2; run++) {
            statements.forEach(sql -> em.createNativeQuery(sql).executeUpdate());
        }

        assertThat(rowOf(legacy)).containsExactly("NOBODY", true);
        assertThat(rowOf(signedIn)).containsExactly("SIGNED_IN", false);
        assertThat(rowOf(nobody)).containsExactly("NOBODY", true);
        assertThat(rowOf(plain)).containsExactly("OWNER", false);
    }

    private Vote persistPoll(User owner, boolean anonymous, VoterVisibility visibility) {
        Vote poll = persistPoll(owner, OffsetDateTime.now(), OffsetDateTime.now().plusDays(1), "A", "B");
        poll.setAnonymous(anonymous);
        poll.setVoterVisibility(visibility);
        return poll;
    }

    private Object[] rowOf(Vote poll) {
        return (Object[]) em.createNativeQuery("SELECT voter_visibility, is_anonymous FROM vomatt.votes WHERE id = :id")
                .setParameter("id", poll.getId()).getSingleResult();
    }
}
