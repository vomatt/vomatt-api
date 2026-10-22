package com.vomatt.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import com.vomatt.entity.InviteList;
import com.vomatt.entity.InviteListMember;
import com.vomatt.entity.User;

/** Runs the real db/migrations/2026-10-22-invite-lists.sql inside the rolled-back test transaction. */
@DisplayName("2026-10-22-invite-lists.sql (Postgres)")
class InviteListMigrationPostgresTest extends PostgresRepositoryTest {

    private static final String SCRIPT = "db/migrations/2026-10-22-invite-lists.sql";

    // Turns the schema.sql tables back into the pre-rename ones, names Postgres generated included
    private static final List<String> TO_GROUPS = List.of(
            "ALTER TABLE vomatt.invite_lists RENAME TO groups",
            "ALTER TABLE vomatt.groups RENAME CONSTRAINT invite_lists_pkey TO groups_pkey",
            "ALTER TABLE vomatt.groups RENAME CONSTRAINT invite_lists_owner_id_fkey TO groups_owner_id_fkey",
            "ALTER INDEX vomatt.idx_invite_lists_owner_id RENAME TO idx_groups_owner_id",
            "ALTER TABLE vomatt.invite_list_members RENAME TO group_members",
            "ALTER TABLE vomatt.group_members RENAME COLUMN invite_list_id TO group_id",
            "ALTER TABLE vomatt.group_members RENAME CONSTRAINT invite_list_members_pkey TO group_members_pkey",
            "ALTER TABLE vomatt.group_members RENAME CONSTRAINT invite_list_members_invite_list_id_user_id_key TO group_members_group_id_user_id_key",
            "ALTER TABLE vomatt.group_members RENAME CONSTRAINT invite_list_members_invite_list_id_fkey TO group_members_group_id_fkey",
            "ALTER TABLE vomatt.group_members RENAME CONSTRAINT invite_list_members_user_id_fkey TO group_members_user_id_fkey",
            "ALTER TABLE vomatt.group_members RENAME CONSTRAINT chk_invite_list_members_user_or_email TO chk_group_members_user_or_email",
            "ALTER INDEX vomatt.idx_invite_list_members_user_id RENAME TO idx_group_members_user_id",
            "ALTER INDEX vomatt.uk_invite_list_members_list_email RENAME TO uk_group_members_group_email",
            "ALTER INDEX vomatt.idx_invite_list_members_email RENAME TO idx_group_members_email");

    @Test
    @DisplayName("should rename groups to invite_lists with the schema.sql names and keep the rows, also on rerun")
    void shouldRenameToSchemaNamesWhenMigrationRuns() throws Exception {
        User owner = User.builder().username("il-" + System.nanoTime()).email("il" + System.nanoTime() + "@example.com").build();
        em.persist(owner);
        InviteList list = new InviteList(owner, "Club");
        em.persist(list);
        InviteListMember member = new InviteListMember(list, "member@example.com");
        em.persist(member);
        em.flush();
        List<String> schemaNames = names();

        TO_GROUPS.forEach(sql -> em.createNativeQuery(sql).executeUpdate());
        assertThat(names()).isEmpty();
        // The script is one DO block; comments are whole lines
        String script = new ClassPathResource(SCRIPT).getContentAsString(StandardCharsets.UTF_8).lines()
                .filter(line -> !line.startsWith("--")).collect(Collectors.joining("\n")).strip();
        for (int run = 0; run < 2; run++) {
            em.createNativeQuery(script).executeUpdate();
        }

        assertThat(names()).isEqualTo(schemaNames);
        assertThat(em.createNativeQuery("SELECT l.name || ' ' || m.email FROM vomatt.invite_list_members m "
                + "JOIN vomatt.invite_lists l ON l.id = m.invite_list_id WHERE m.id = :id")
                .setParameter("id", member.getId()).getSingleResult()).isEqualTo("Club member@example.com");
    }

    @SuppressWarnings("unchecked")
    private List<String> names() {
        return em.createNativeQuery("""
                SELECT CAST(CAST(conrelid AS regclass) AS text) || ' ' || conname FROM pg_constraint WHERE CAST(CAST(conrelid AS regclass) AS text) LIKE 'vomatt.invite_list%'
                UNION ALL SELECT tablename || ' ' || indexname FROM pg_indexes WHERE schemaname = 'vomatt' AND tablename LIKE 'invite_list%'
                ORDER BY 1""").getResultList();
    }
}
