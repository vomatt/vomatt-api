package com.vomatt.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;

import org.hibernate.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import com.vomatt.entity.Group;
import com.vomatt.entity.GroupMember;
import com.vomatt.entity.Invitee;
import com.vomatt.entity.User;
import com.vomatt.entity.Vote;

/** Runs the real db/migrations/2026-10-14-canonical-gmail.sql inside the rolled-back test transaction. */
@DisplayName("2026-10-14-canonical-gmail.sql (Postgres)")
class CanonicalGmailMigrationPostgresTest extends PostgresRepositoryTest {

    private static final ClassPathResource MIGRATION = new ClassPathResource("db/migrations/2026-10-14-canonical-gmail.sql");

    private final String tag = String.valueOf(System.nanoTime());

    private User user(String email) {
        User user = User.builder().username("cg-" + System.nanoTime()).email(email).build();
        em.persist(user);
        return user;
    }

    private String emailOf(Object entity, String table) {
        return (String) em.createNativeQuery("SELECT email FROM vomatt." + table + " WHERE id = :id")
                .setParameter("id", em.getEntityManagerFactory().getPersistenceUnitUtil().getIdentifier(entity))
                .getSingleResult();
    }

    @Test
    @DisplayName("should rewrite non-colliding rows to the canonical form, leave colliding ones and list the colliding users, also on a rerun")
    void shouldRewriteOnlyNonCollidingRowsWhenMigrationRuns() throws IOException {
        User lone = user("Lo.ne" + tag + "+x@googlemail.com");
        User clashA = user("cl.ash" + tag + "@gmail.com");
        User clashB = user("clash" + tag + "+y@gmail.com");
        User other = user("o.ther+z" + tag + "@example.com");
        Vote poll = persistPoll(lone, OffsetDateTime.now().minusHours(1), OffsetDateTime.now().plusDays(1), "A", "B");
        Invitee invitee = new Invitee(poll, "in.v" + tag + "+p@gmail.com");
        Invitee inviteeClashA = new Invitee(poll, "dup" + tag + "@gmail.com");
        Invitee inviteeClashB = new Invitee(poll, "d.up" + tag + "@gmail.com");
        Group group = new Group(lone, "Club");
        em.persist(group);
        GroupMember member = new GroupMember(group, "me.m" + tag + "+g@googlemail.com");
        List.of(invitee, inviteeClashA, inviteeClashB).forEach(em::persist);
        em.persist(member);
        em.flush();

        runMigration();
        List<Object[]> collisions = runMigration();
        em.clear();

        assertThat(emailOf(lone, "users")).isEqualTo("lone" + tag + "@gmail.com");
        assertThat(emailOf(clashA, "users")).isEqualTo("cl.ash" + tag + "@gmail.com");
        assertThat(emailOf(clashB, "users")).isEqualTo("clash" + tag + "+y@gmail.com");
        assertThat(emailOf(other, "users")).isEqualTo("o.ther+z" + tag + "@example.com");
        assertThat(emailOf(invitee, "vote_invitees")).isEqualTo("inv" + tag + "@gmail.com");
        assertThat(emailOf(inviteeClashA, "vote_invitees")).isEqualTo("dup" + tag + "@gmail.com");
        assertThat(emailOf(inviteeClashB, "vote_invitees")).isEqualTo("d.up" + tag + "@gmail.com");
        assertThat(emailOf(member, "group_members")).isEqualTo("mem" + tag + "@gmail.com");
        assertThat(collisions).filteredOn(row -> row[0].equals("clash" + tag + "@gmail.com")).singleElement()
                .satisfies(row -> assertThat((String[]) row[2])
                        .containsExactly("cl.ash" + tag + "@gmail.com", "clash" + tag + "+y@gmail.com"));
        assertThat(collisions).noneMatch(row -> row[0].equals("lone" + tag + "@gmail.com"));
    }

    /** Runs the whole file, then returns the rows of its final SELECT (ScriptUtils discards query results). */
    @SuppressWarnings("unchecked")
    private List<Object[]> runMigration() throws IOException {
        em.unwrap(Session.class).doWork(connection -> ScriptUtils.executeSqlScript(connection, MIGRATION));
        String sql = MIGRATION.getContentAsString(StandardCharsets.UTF_8).strip();
        String finalSelect = sql.substring(sql.lastIndexOf("\nSELECT "), sql.length() - 1);
        return em.createNativeQuery(finalSelect).getResultList();
    }
}
