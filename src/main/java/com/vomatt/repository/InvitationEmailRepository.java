package com.vomatt.repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/** Sign-up invitation emails sent, per sender and address ({@code invitation_emails}). */
@Repository
@RequiredArgsConstructor
public class InvitationEmailRepository {

    private final EntityManager em;

    /**
     * The addresses mailed about the scope at or after {@code since}, by anyone: a community account has several
     * managers, so another one revoking and re-inviting must not mail the address again.
     */
    @SuppressWarnings("unchecked")
    public List<String> findSentSince(UUID scopeId, Collection<String> emails, OffsetDateTime since) {
        return em.createNativeQuery("""
                SELECT email FROM vomatt.invitation_emails
                WHERE scope_id = :scopeId AND email IN (:emails) AND sent_at >= :since""")
                .setParameter("scopeId", scopeId)
                .setParameter("emails", emails)
                .setParameter("since", since)
                .getResultList();
    }

    /** When each of the addresses was last mailed about the scope, by anyone; addresses never mailed are absent. */
    @SuppressWarnings("unchecked")
    public Map<String, OffsetDateTime> findLastSent(UUID scopeId, Collection<String> emails) {
        if (emails.isEmpty()) {
            return Map.of();
        }
        List<Object[]> rows = em.createNativeQuery("""
                SELECT email, max(sent_at) FROM vomatt.invitation_emails
                WHERE scope_id = :scopeId AND email IN (:emails) GROUP BY email""")
                .setParameter("scopeId", scopeId)
                .setParameter("emails", emails)
                .getResultList();
        return rows.stream().collect(Collectors.toMap(r -> (String) r[0], r -> toOffsetDateTime(r[1])));
    }

    private static OffsetDateTime toOffsetDateTime(Object value) {
        return switch (value) {
            case OffsetDateTime time -> time;
            case java.time.Instant instant -> instant.atOffset(java.time.ZoneOffset.UTC);
            case java.sql.Timestamp timestamp -> timestamp.toInstant().atOffset(java.time.ZoneOffset.UTC);
            default -> throw new IllegalStateException("Unexpected sent_at type " + value.getClass());
        };
    }

    /** Records the sender mailed the addresses about the scope now. */
    public void recordSent(UUID senderId, UUID scopeId, Collection<String> emails, OffsetDateTime now) {
        em.createNativeQuery("""
                INSERT INTO vomatt.invitation_emails (sender_id, scope_id, email, sent_at)
                SELECT :senderId, :scopeId, e, :now FROM unnest(CAST(:emails AS text[])) AS e
                ON CONFLICT (sender_id, scope_id, email) DO UPDATE SET sent_at = EXCLUDED.sent_at""")
                .setParameter("senderId", senderId)
                .setParameter("scopeId", scopeId)
                .setParameter("emails", emails.toArray(String[]::new))
                .setParameter("now", now)
                .executeUpdate();
    }
}
