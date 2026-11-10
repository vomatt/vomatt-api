package com.vomatt.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import com.vomatt.entity.User;

/** Runs the real db/migration/V20261110213027__official_to_community.sql again inside the rolled-back test transaction. */
@DisplayName("V20261110213027__official_to_community.sql (Postgres)")
class OfficialToCommunityMigrationPostgresTest extends PostgresRepositoryTest {

    private static final String SCRIPT = "db/migration/V20261110213027__official_to_community.sql";

    @Test
    @DisplayName("should move the official role, member table and audit logs to community, also on rerun")
    void shouldRenameOfficialToCommunityWhenMigrationRuns() throws Exception {
        User member = User.builder().username("u" + System.nanoTime()).email("m" + System.nanoTime() + "@example.com").build();
        em.persist(member);
        em.flush();
        // recreate the pre-migration shape: an 'official' user without email or phone, its member row and an audit log
        em.createNativeQuery("""
                ALTER TABLE vomatt.users DROP CONSTRAINT chk_users_identifier;
                ALTER TABLE vomatt.users ADD CONSTRAINT chk_users_identifier
                    CHECK (email IS NOT NULL OR phone_number IS NOT NULL OR 'official' = ANY (roles));
                ALTER TABLE vomatt.community_members RENAME COLUMN community_user_id TO official_user_id;
                ALTER TABLE vomatt.community_members RENAME TO official_members;
                ALTER INDEX vomatt.idx_community_members_user RENAME TO idx_official_members_user""").executeUpdate();
        String officialId = (String) em.createNativeQuery("""
                INSERT INTO vomatt.users (id, username, roles, active, created_at, updated_at)
                VALUES (gen_random_uuid(), 'o' || clock_timestamp()::text, ARRAY['official'], true, now(), now())
                RETURNING id::text""").getSingleResult();
        em.createNativeQuery("""
                INSERT INTO vomatt.official_members (official_user_id, user_id, role)
                VALUES (CAST(:o AS uuid), :m, 'OWNER')""").setParameter("o", officialId).setParameter("m", member.getId())
                .executeUpdate();
        em.createNativeQuery("""
                INSERT INTO vomatt.audit_logs (id, action, resource_type, resource_id, created_at)
                VALUES (gen_random_uuid(), 'CREATE', 'OFFICIAL', :o, now())""").setParameter("o", officialId).executeUpdate();

        String script = new ClassPathResource(SCRIPT).getContentAsString(StandardCharsets.UTF_8);
        for (int run = 0; run < 2; run++) {
            em.createNativeQuery(script).executeUpdate();
        }

        assertThat(scalar("SELECT array_to_string(roles, ',') FROM vomatt.users WHERE id = CAST(:o AS uuid)", officialId))
                .isEqualTo("community");
        assertThat(scalar("SELECT role FROM vomatt.community_members WHERE community_user_id = CAST(:o AS uuid)", officialId))
                .isEqualTo("OWNER");
        assertThat(scalar("SELECT resource_type FROM vomatt.audit_logs WHERE resource_id = :o", officialId))
                .isEqualTo("COMMUNITY");
    }

    private Object scalar(String sql, String officialId) {
        return em.createNativeQuery(sql).setParameter("o", officialId).getSingleResult();
    }
}
