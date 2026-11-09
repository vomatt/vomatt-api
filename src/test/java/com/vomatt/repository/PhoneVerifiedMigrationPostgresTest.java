package com.vomatt.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import com.vomatt.entity.User;

/** Runs the real db/migration/V20261109212717__phone_verified_and_gender.sql again inside the rolled-back test transaction. */
@DisplayName("V20261109212717__phone_verified_and_gender.sql (Postgres)")
class PhoneVerifiedMigrationPostgresTest extends PostgresRepositoryTest {

    private static final String SCRIPT = "db/migration/V20261109212717__phone_verified_and_gender.sql";

    @Test
    @DisplayName("should mark phone numbers verified at sign-up, leave email users and already verified ones alone, also on rerun")
    void shouldBackfillPhoneVerifiedAtWhenMigrationRuns() throws Exception {
        long n = System.nanoTime();
        User phone = persist(null, "+8869" + "%08d".formatted(n % 100000000L), null);
        User email = persist("gina" + n + "@example.com", null, null);
        OffsetDateTime boundAt = OffsetDateTime.parse("2026-11-01T10:00:00+08:00");
        User bound = persist("hank" + n + "@example.com", "+8869" + "%08d".formatted((n + 1) % 100000000L), boundAt);
        em.flush();
        // created_at is set by auditing; pin it so the backfill value is checkable
        em.createNativeQuery("UPDATE vomatt.users SET created_at = '2026-01-02T03:04:05Z' WHERE id IN (:a, :b)")
                .setParameter("a", phone.getId()).setParameter("b", bound.getId()).executeUpdate();
        em.createNativeQuery("UPDATE vomatt.users SET phone_verified_at = NULL WHERE id = :id")
                .setParameter("id", phone.getId()).executeUpdate();

        String script = new ClassPathResource(SCRIPT).getContentAsString(StandardCharsets.UTF_8);
        for (int run = 0; run < 2; run++) {
            em.createNativeQuery(script).executeUpdate();
        }
        em.clear();

        assertThat(reload(phone).getPhoneVerifiedAt()).isAtSameInstantAs(OffsetDateTime.parse("2026-01-02T03:04:05Z"));
        assertThat(reload(email).getPhoneVerifiedAt()).isNull();
        assertThat(reload(bound).getPhoneVerifiedAt()).isAtSameInstantAs(boundAt);
    }

    private User persist(String email, String phoneNumber, OffsetDateTime phoneVerifiedAt) {
        User user = User.builder().username("u" + System.nanoTime()).email(email).phoneNumber(phoneNumber)
                .phoneVerifiedAt(phoneVerifiedAt).build();
        em.persist(user);
        return user;
    }

    private User reload(User user) {
        return em.find(User.class, user.getId());
    }
}
