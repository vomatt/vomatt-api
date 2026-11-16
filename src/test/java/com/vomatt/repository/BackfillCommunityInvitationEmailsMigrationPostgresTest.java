package com.vomatt.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import com.vomatt.entity.User;

/** Runs the real db/migration/V20261116220000__backfill_community_invitation_emails.sql again inside the rolled-back test transaction. */
@DisplayName("V20261116220000__backfill_community_invitation_emails.sql (Postgres)")
class BackfillCommunityInvitationEmailsMigrationPostgresTest extends PostgresRepositoryTest {

    private static final String SCRIPT = "db/migration/V20261116220000__backfill_community_invitation_emails.sql";

    @Test
    void shouldRecordPendingEmailInvitationsAsSentWhenMigrationReruns() throws Exception {
        User community = persistUser("bf-c-" + UUID.randomUUID().toString().substring(0, 8));
        User inviter = persistUser("bf-i-" + UUID.randomUUID().toString().substring(0, 8));
        User invitee = persistUser("bf-u-" + UUID.randomUUID().toString().substring(0, 8));
        em.flush();
        OffsetDateTime created = OffsetDateTime.now().minusDays(1);
        insertInvitation(community, null, "pending@example.com", inviter, created, created.plusDays(7));
        insertInvitation(community, null, "expired@example.com", inviter, created.minusDays(8), created.minusDays(1));
        insertInvitation(community, invitee, null, inviter, created, created.plusDays(7));

        String script = new ClassPathResource(SCRIPT).getContentAsString(StandardCharsets.UTF_8);
        for (int run = 0; run < 2; run++) {
            em.createNativeQuery(script).executeUpdate();
        }

        @SuppressWarnings("unchecked")
        List<String> recorded = em.createNativeQuery("SELECT email FROM vomatt.invitation_emails WHERE scope_id = :id")
                .setParameter("id", community.getId()).getResultList();
        assertThat(recorded).containsExactly("pending@example.com");
    }

    private void insertInvitation(User community, User user, String email, User inviter, OffsetDateTime createdAt,
                                  OffsetDateTime expiresAt) {
        em.createNativeQuery("""
                INSERT INTO vomatt.community_invitations (id, community_user_id, user_id, email, role, invited_by, created_at, expires_at)
                VALUES (:id, :community, :user, :email, 'EDITOR', :inviter, :createdAt, :expiresAt)""")
                .setParameter("id", UUID.randomUUID())
                .setParameter("community", community.getId())
                .setParameter("user", user == null ? null : user.getId())
                .setParameter("email", email)
                .setParameter("inviter", inviter.getId())
                .setParameter("createdAt", createdAt)
                .setParameter("expiresAt", expiresAt)
                .executeUpdate();
    }
}
