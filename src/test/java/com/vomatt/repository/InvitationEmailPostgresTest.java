package com.vomatt.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import com.vomatt.entity.User;

@Import(InvitationEmailRepository.class)
@DisplayName("Invitation email limits (Postgres)")
class InvitationEmailPostgresTest extends PostgresRepositoryTest {

    private static final Duration WINDOW = Duration.ofHours(12);
    private static final Duration LOCK = Duration.ofDays(7);

    @Autowired InvitationEmailRepository repository;

    private final OffsetDateTime start = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);

    private void send(User sender, OffsetDateTime at, String... emails) {
        repository.recordSent(sender.getId(), List.of(emails), at, at.minus(WINDOW), 3, at.plus(LOCK));
    }

    @Test
    void shouldLockPairWhenThirdEmailFallsWithinWindow() {
        User sender = persistUser("ie-s-" + UUID.randomUUID());
        User other = persistUser("ie-o-" + UUID.randomUUID());
        send(sender, start, "a@example.com", "b@example.com");
        send(sender, start.plusHours(1), "a@example.com");
        send(other, start.plusHours(1), "a@example.com");
        assertThat(repository.findLocked(sender.getId(), List.of("a@example.com"), start.plusHours(2))).isEmpty();

        OffsetDateTime third = start.plusHours(11);
        send(sender, third, "a@example.com");

        assertThat(repository.findLocked(sender.getId(), List.of("a@example.com", "b@example.com"), third))
                .containsOnlyKeys("a@example.com")
                .hasEntrySatisfying("a@example.com", until -> assertThat(until).isAtSameInstantAs(third.plus(LOCK)));
        // Per sender: another sender mailing the same address is not locked
        assertThat(repository.findLocked(other.getId(), List.of("a@example.com"), third)).isEmpty();
        assertThat(repository.findLocks(third)).extracting(InvitationEmailRepository.Lock::email).containsExactly("a@example.com");
        assertThat(repository.findLocked(sender.getId(), List.of("a@example.com"), third.plus(LOCK))).isEmpty();
    }

    @Test
    void shouldStartNewWindowWhenEmailsAreMoreThanWindowApart() {
        User sender = persistUser("ie-w-" + UUID.randomUUID());
        send(sender, start, "a@example.com");
        send(sender, start.plusHours(6), "a@example.com");
        // 12 hours after the window started: counts as the first of a new window
        send(sender, start.plusHours(12), "a@example.com");
        send(sender, start.plusHours(13), "a@example.com");

        assertThat(repository.findLocked(sender.getId(), List.of("a@example.com"), start.plusHours(13))).isEmpty();
    }

    @Test
    void shouldResetCountWhenAdminUnlocks() {
        User sender = persistUser("ie-u-" + UUID.randomUUID());
        for (int i = 0; i < 3; i++) {
            send(sender, start.plusMinutes(i), "a@example.com");
        }
        assertThat(repository.unlock(sender.getId(), "a@example.com", start.plusMinutes(3))).isTrue();
        assertThat(repository.unlock(sender.getId(), "a@example.com", start.plusMinutes(3))).isFalse();

        send(sender, start.plusMinutes(4), "a@example.com");
        assertThat(repository.findLocked(sender.getId(), List.of("a@example.com"), start.plusMinutes(4))).isEmpty();
    }
}
