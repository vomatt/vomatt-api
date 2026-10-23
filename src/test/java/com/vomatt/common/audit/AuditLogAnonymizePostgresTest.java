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
        AuditLog theirs = new AuditLog(other, "bob", "CREATE", "VOTE", UUID.randomUUID().toString());
        em.persist(mine);
        em.persist(theirs);
        em.flush();

        assertThat(repository.anonymizeByUserId(deleted)).isEqualTo(1);
        em.clear();

        AuditLog anonymized = repository.findById(mine.getId()).orElseThrow();
        assertThat(anonymized.getUserId()).isNull();
        assertThat(anonymized.getUsername()).isNull();
        assertThat(anonymized.getIpAddress()).isNull();
        assertThat(anonymized.getUserAgent()).isNull();
        assertThat(anonymized.getAction()).isEqualTo("SUSPEND");
        assertThat(anonymized.getResourceId()).isEqualTo(other);
        assertThat(repository.findById(theirs.getId()).orElseThrow().getUserId()).isEqualTo(other);
    }
}
