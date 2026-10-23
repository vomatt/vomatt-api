package com.vomatt.common.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.vomatt.repository.PostgresRepositoryTest;

@DisplayName("Audit log anonymization on account deletion (Postgres)")
class AuditLogAnonymizePostgresTest extends PostgresRepositoryTest {

    @Autowired AuditLogRepository repository;

    @Test
    @DisplayName("should strip actor identity from the user's entries and leave other users' entries alone")
    void shouldAnonymizeActorWhenUserIsDeleted() {
        String deleted = UUID.randomUUID().toString();
        String other = UUID.randomUUID().toString();
        AuditLog mine = new AuditLog(deleted, "alice", "SUSPEND", "USER", other);
        mine.setIpAddress("203.0.113.7");
        mine.setUserAgent("ua");
        AuditLog self = new AuditLog(deleted, "alice", "DELETE", "USER", deleted.toUpperCase());
        self.setRequestUri("/api/users/" + deleted);
        AuditLog theirs = new AuditLog(other, "bob", "CREATE", "VOTE", UUID.randomUUID().toString());
        em.persist(mine);
        em.persist(self);
        em.persist(theirs);
        em.flush();

        assertThat(repository.anonymizeByUserId(deleted)).isEqualTo(2);
        em.clear();

        // The entry about the user's own account loses the id wherever it is spelled
        AuditLog selfAnonymized = repository.findById(self.getId()).orElseThrow();
        assertThat(selfAnonymized.getUserId()).isNull();
        assertThat(selfAnonymized.getResourceId()).isNull();
        assertThat(selfAnonymized.getRequestUri()).isNull();
        assertThat(selfAnonymized.getAction()).isEqualTo("DELETE");

        AuditLog anonymized = repository.findById(mine.getId()).orElseThrow();
        assertThat(anonymized.getUserId()).isNull();
        assertThat(anonymized.getUsername()).isNull();
        assertThat(anonymized.getIpAddress()).isNull();
        assertThat(anonymized.getUserAgent()).isNull();
        assertThat(anonymized.getAction()).isEqualTo("SUSPEND");
        assertThat(anonymized.getResourceId()).isEqualTo(other);
        assertThat(repository.findById(theirs.getId()).orElseThrow().getUserId()).isEqualTo(other);
    }

    @Test
    @DisplayName("should anonymize entries whose actor account no longer exists and leave existing actors alone")
    void shouldAnonymizeOrphanedActorsWhenSweeping() {
        com.vomatt.entity.User alive = persistUser("audit-alive-" + UUID.randomUUID().toString().substring(0, 8));
        String ghost = UUID.randomUUID().toString();
        AuditLog byAlive = new AuditLog(alive.getId().toString(), null, "CREATE", "VOTE", UUID.randomUUID().toString());
        AuditLog byGhost = new AuditLog(ghost, "ghost", "BLOCK", "USER", alive.getId().toString());
        byGhost.setIpAddress("203.0.113.7");
        AuditLog ghostSelf = new AuditLog(ghost, "ghost", "DELETE", "USER", ghost);
        ghostSelf.setRequestUri("/api/users/" + ghost);
        em.persist(byAlive);
        em.persist(byGhost);
        em.persist(ghostSelf);
        em.flush();

        assertThat(repository.anonymizeOrphanedActors()).isGreaterThanOrEqualTo(2);
        em.clear();

        assertThat(repository.findById(byAlive.getId()).orElseThrow().getUserId()).isEqualTo(alive.getId().toString());
        AuditLog swept = repository.findById(byGhost.getId()).orElseThrow();
        assertThat(swept.getUserId()).isNull();
        assertThat(swept.getIpAddress()).isNull();
        assertThat(swept.getResourceId()).isEqualTo(alive.getId().toString());
        AuditLog sweptSelf = repository.findById(ghostSelf.getId()).orElseThrow();
        assertThat(sweptSelf.getResourceId()).isNull();
        assertThat(sweptSelf.getRequestUri()).isNull();
    }
}
