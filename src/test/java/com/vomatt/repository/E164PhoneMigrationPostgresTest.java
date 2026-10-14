package com.vomatt.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import com.vomatt.entity.User;

/** Runs the real db/migrations/2026-10-14-e164-phone.sql inside the rolled-back test transaction. */
@DisplayName("2026-10-14-e164-phone.sql (Postgres)")
class E164PhoneMigrationPostgresTest extends PostgresRepositoryTest {

    private static final String SCRIPT = "db/migrations/2026-10-14-e164-phone.sql";

    @Test
    @DisplayName("should convert 09 numbers to +886 and list the rows that would collide")
    void shouldConvertAndReportCollisionsWhenMigrationRuns() throws Exception {
        String suffix = String.valueOf(System.nanoTime() % 100_000_000L);
        String free = "09" + "%08d".formatted(Long.parseLong(suffix));
        String taken = "09" + "%08d".formatted((Long.parseLong(suffix) + 1) % 100_000_000L);
        User converted = persistPhoneUser("e164-free-" + suffix, free);
        User legacy = persistPhoneUser("e164-legacy-" + suffix, taken);
        User e164 = persistPhoneUser("e164-owner-" + suffix, "+886" + taken.substring(1));
        em.flush();

        // The script holds one UPDATE then the collision SELECT; comments are whole lines and no literal contains ';'
        String script = new ClassPathResource(SCRIPT).getContentAsString(StandardCharsets.UTF_8).lines()
                .filter(line -> !line.startsWith("--")).collect(Collectors.joining("\n"));
        List<String> statements = Arrays.stream(script.split(";")).map(String::strip).filter(sql -> !sql.isEmpty()).toList();
        assertThat(statements).hasSize(2);
        // Run twice: the migration must be re-runnable
        List<?> collisions = List.of();
        for (int run = 0; run < 2; run++) {
            em.createNativeQuery(statements.getFirst()).executeUpdate();
            collisions = em.createNativeQuery(statements.getLast()).getResultList();
        }

        assertThat(phoneOf(converted)).isEqualTo("+886" + free.substring(1));
        assertThat(phoneOf(legacy)).isEqualTo(taken);
        assertThat(phoneOf(e164)).isEqualTo("+886" + taken.substring(1));
        assertThat(collisions).anySatisfy(row -> {
            Object[] cols = (Object[]) row;
            assertThat(cols[0]).hasToString(legacy.getId().toString());
            assertThat(cols[1]).isEqualTo(taken);
            assertThat(cols[2]).hasToString(e164.getId().toString());
        });
        assertThat(collisions).noneSatisfy(row -> assertThat(((Object[]) row)[0]).hasToString(converted.getId().toString()));
    }

    private User persistPhoneUser(String username, String phone) {
        User user = User.builder().username(username).phoneNumber(phone).build();
        em.persist(user);
        return user;
    }

    private String phoneOf(User user) {
        return (String) em.createNativeQuery("SELECT phone_number FROM vomatt.users WHERE id = :id")
                .setParameter("id", user.getId()).getSingleResult();
    }
}
