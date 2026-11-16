package com.vomatt.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import com.vomatt.entity.User;

@Import(InvitationEmailRepository.class)
@DisplayName("Invitation emails sent (Postgres)")
class InvitationEmailPostgresTest extends PostgresRepositoryTest {

    @Autowired InvitationEmailRepository repository;

    @Test
    void shouldFindAddressMailedByAnotherSenderWhenSameScope() {
        User first = persistUser("ie-first-" + UUID.randomUUID());
        UUID scope = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        repository.recordSent(first.getId(), scope, List.of("a@example.com"), now);

        // No sender in the lookup: another manager who revoked the invitation and invites the same address again
        assertThat(repository.findSentSince(scope, List.of("a@example.com", "b@example.com"), now.minusDays(3)))
                .containsExactly("a@example.com");
        assertThat(repository.findSentSince(UUID.randomUUID(), List.of("a@example.com"), now.minusDays(3))).isEmpty();
        assertThat(repository.findSentSince(scope, List.of("a@example.com"), now.plusSeconds(1))).isEmpty();
    }
}
