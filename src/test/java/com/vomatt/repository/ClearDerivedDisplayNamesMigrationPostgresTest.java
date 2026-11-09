package com.vomatt.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import com.vomatt.entity.User;

/** Runs the real db/migration/V20261106092526__clear_derived_display_names.sql again inside the rolled-back test transaction. */
@DisplayName("V20261106092526__clear_derived_display_names.sql (Postgres)")
class ClearDerivedDisplayNamesMigrationPostgresTest extends PostgresRepositoryTest {

    private static final String SCRIPT = "db/migration/V20261106092526__clear_derived_display_names.sql";

    @Test
    @DisplayName("should clear display names made from the email or phone, keep chosen ones, also on rerun")
    void shouldClearDerivedDisplayNamesWhenMigrationRuns() throws Exception {
        long n = System.nanoTime();
        User localPart = persist("dana" + n + "@example.com", null, "dana" + n);
        User gmailDotted = persist("danadawson" + n + "@gmail.com", null, "dana.dawson" + n);
        User phone = persist(null, "+8869" + "%08d".formatted(n % 100000000L), null);
        phone.setDisplayName(phone.getPhoneNumber());
        User legacyPhone = persist(null, "+8869" + "%08d".formatted((n + 1) % 100000000L), null);
        legacyPhone.setDisplayName("09" + legacyPhone.getPhoneNumber().substring(5));
        User mixedCase = persist("dana.dawson" + n + "@example.com", null, "Dana.Dawson" + n);
        User chosen = persist("erin" + n + "@example.com", null, "Erin");
        User otherDomainDotted = persist("frank" + n + "@example.com", null, "fr.ank" + n);
        em.flush();

        String script = new ClassPathResource(SCRIPT).getContentAsString(StandardCharsets.UTF_8);
        for (int run = 0; run < 2; run++) {
            em.createNativeQuery(script).executeUpdate();
        }
        em.clear();

        assertThat(displayName(localPart)).isNull();
        assertThat(displayName(gmailDotted)).isNull();
        assertThat(displayName(phone)).isNull();
        assertThat(displayName(legacyPhone)).isNull();
        assertThat(displayName(mixedCase)).isNull();
        assertThat(displayName(chosen)).isEqualTo("Erin");
        assertThat(displayName(otherDomainDotted)).isEqualTo("fr.ank" + n);
    }

    private User persist(String email, String phoneNumber, String displayName) {
        User user = User.builder().username("u" + System.nanoTime()).email(email).phoneNumber(phoneNumber)
                .displayName(displayName).build();
        em.persist(user);
        return user;
    }

    private String displayName(User user) {
        return em.find(User.class, user.getId()).getDisplayName();
    }
}
