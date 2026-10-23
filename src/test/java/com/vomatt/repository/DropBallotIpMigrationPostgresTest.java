package com.vomatt.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** Runs the real db/migrations/2026-10-23-drop-ballot-ip.sql inside the rolled-back test transaction. */
@DisplayName("2026-10-23-drop-ballot-ip.sql (Postgres)")
class DropBallotIpMigrationPostgresTest extends PostgresRepositoryTest {

    private static final String SCRIPT = "db/migrations/2026-10-23-drop-ballot-ip.sql";

    @Test
    @DisplayName("should drop the column and stay a no-op when run again")
    void shouldDropIpColumnWhenMigrationRuns() throws Exception {
        // schema.sql no longer creates the column: recreate the pre-migration shape first
        em.createNativeQuery("ALTER TABLE vomatt.user_votes ADD COLUMN IF NOT EXISTS ip_address VARCHAR(45)").executeUpdate();
        assertThat(hasIpColumn()).isTrue();

        String sql = new ClassPathResource(SCRIPT).getContentAsString(StandardCharsets.UTF_8).lines()
                .filter(line -> !line.startsWith("--")).collect(Collectors.joining("\n")).strip();
        for (int run = 0; run < 2; run++) {
            em.createNativeQuery(sql).executeUpdate();
        }

        assertThat(hasIpColumn()).isFalse();
    }

    private boolean hasIpColumn() {
        return ((Number) em.createNativeQuery("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = 'vomatt' AND table_name = 'user_votes' AND column_name = 'ip_address'""")
                .getSingleResult()).intValue() == 1;
    }
}
