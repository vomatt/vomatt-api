package com.vomatt.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.vomatt.entity.User;

/** Sign-in runs outside a transaction, so this test commits for real instead of rolling back. */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RecordLoginPostgresTest extends PostgresRepositoryTest {

    @Autowired TransactionTemplate tx;
    @Autowired UserRepository userRepository;

    @Test
    void shouldWriteOnlyLoginTimesWhenSuspensionCommittedSinceUserWasRead() {
        User user = tx.execute(s -> persistUser("rl-" + UUID.randomUUID()));
        // Suspended after the sign-in read the row
        tx.executeWithoutResult(s -> em.createNativeQuery("UPDATE vomatt.users SET active = false WHERE id = :id")
                .setParameter("id", user.getId()).executeUpdate());
        OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);

        userRepository.recordLogin(user.getId(), now);

        User saved = userRepository.findById(user.getId()).orElseThrow();
        tx.executeWithoutResult(s -> em.createNativeQuery("DELETE FROM vomatt.users WHERE id = :id")
                .setParameter("id", user.getId()).executeUpdate());
        assertThat(saved.isActive()).isFalse();
        assertThat(saved.getLastLoginAt()).isEqualTo(now.toInstant().atOffset(saved.getLastLoginAt().getOffset()));
        assertThat(saved.getUpdatedAt()).isEqualTo(saved.getLastLoginAt());
    }
}
