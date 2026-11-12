package com.vomatt.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** Runs the real db/migration/V20261112094616__community_lock_reason.sql again inside the rolled-back test transaction. */
@DisplayName("V20261112094616__community_lock_reason.sql (Postgres)")
class CommunityLockReasonMigrationPostgresTest extends PostgresRepositoryTest {

    private static final String SCRIPT = "db/migration/V20261112094616__community_lock_reason.sql";

    private final OffsetDateTime now = OffsetDateTime.now();

    private UUID user(boolean locked) {
        UUID id = UUID.randomUUID();
        em.createNativeQuery("INSERT INTO vomatt.users (id, username, email, locked_at, created_at, updated_at) "
                        + "VALUES (:id, :u, :e, :locked, now(), now())")
                .setParameter("id", id).setParameter("u", "lr" + id.toString().substring(0, 8))
                .setParameter("e", id + "@test.local").setParameter("locked", locked ? now : null).executeUpdate();
        return id;
    }

    private UUID lockAudit(UUID community, String reason, OffsetDateTime at) {
        UUID id = UUID.randomUUID();
        em.createNativeQuery("INSERT INTO vomatt.audit_logs (id, action, resource_type, resource_id, details, created_at) "
                        + "VALUES (:id, 'LOCK', 'COMMUNITY', :rid, :details, :at)")
                .setParameter("id", id).setParameter("rid", community.toString()).setParameter("details", "reason=" + reason)
                .setParameter("at", at).executeUpdate();
        return id;
    }

    private void read(UUID user, UUID source) {
        em.createNativeQuery("INSERT INTO vomatt.notification_reads (user_id, kind, source_id) VALUES (:u, 'COMMUNITY_LOCKED', :s)")
                .setParameter("u", user).setParameter("s", source).executeUpdate();
    }

    @Test
    void shouldBackfillReasonAndMoveReadStateWhenMigrationReruns() throws Exception {
        UUID owner = user(false);
        // Locked by choice, unlocked, then locked by the deadline: the latest LOCK is the current one
        UUID relocked = user(true);
        UUID earlier = lockAudit(relocked, "USER_CHOICE", now.minusDays(2));
        UUID current = lockAudit(relocked, "SELECTION_DEADLINE", now.minusDays(1));
        UUID noAudit = user(true);
        UUID unlocked = user(false);
        lockAudit(unlocked, "SELECTION_DEADLINE", now.minusDays(1));
        read(owner, earlier);
        read(owner, current);
        em.createNativeQuery("UPDATE vomatt.users SET lock_reason = NULL WHERE id IN (:ids)")
                .setParameter("ids", List.of(relocked, noAudit, unlocked)).executeUpdate();

        String script = new ClassPathResource(SCRIPT).getContentAsString(StandardCharsets.UTF_8);
        for (int run = 0; run < 2; run++) {
            em.createNativeQuery(script).executeUpdate();
        }

        assertThat(reason(relocked)).isEqualTo("SELECTION_DEADLINE");
        assertThat(reason(noAudit)).isNull();
        assertThat(reason(unlocked)).isNull();
        assertThat(em.createNativeQuery("SELECT source_id FROM vomatt.notification_reads WHERE user_id = :u AND kind = 'COMMUNITY_LOCKED'")
                .setParameter("u", owner).getResultList()).containsExactly(relocked);
    }

    private Object reason(UUID id) {
        return em.createNativeQuery("SELECT lock_reason FROM vomatt.users WHERE id = :id").setParameter("id", id).getSingleResult();
    }
}
