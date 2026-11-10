package com.vomatt.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** Runs the real db/migration/V20261110235309__user_plan.sql again inside the rolled-back test transaction. */
@DisplayName("V20261110235309__user_plan.sql (Postgres)")
class UserPlanMigrationPostgresTest extends PostgresRepositoryTest {

    private static final String SCRIPT = "db/migration/V20261110235309__user_plan.sql";

    @Test
    @DisplayName("should put accounts on FREE by default and keep a set plan on rerun")
    void shouldDefaultToFreeAndKeepPlanWhenMigrationReruns() throws Exception {
        UUID free = UUID.randomUUID();
        UUID pro = UUID.randomUUID();
        for (UUID id : new UUID[] { free, pro }) {
            em.createNativeQuery("INSERT INTO vomatt.users (id, username, email, created_at, updated_at) VALUES (:id, :u, :e, now(), now())")
                    .setParameter("id", id).setParameter("u", "plan" + id.toString().substring(0, 8))
                    .setParameter("e", id + "@test.local").executeUpdate();
        }
        em.createNativeQuery("UPDATE vomatt.users SET plan = 'PRO' WHERE id = :id").setParameter("id", pro).executeUpdate();

        String script = new ClassPathResource(SCRIPT).getContentAsString(StandardCharsets.UTF_8);
        for (int run = 0; run < 2; run++) {
            em.createNativeQuery(script).executeUpdate();
        }

        assertThat(plan(free)).isEqualTo("FREE");
        assertThat(plan(pro)).isEqualTo("PRO");
        assertThat(em.createNativeQuery("SELECT stripe_customer_id FROM vomatt.users WHERE id = :id").setParameter("id", pro)
                .getSingleResult()).isNull();
    }

    private Object plan(UUID id) {
        return em.createNativeQuery("SELECT plan FROM vomatt.users WHERE id = :id").setParameter("id", id).getSingleResult();
    }
}
