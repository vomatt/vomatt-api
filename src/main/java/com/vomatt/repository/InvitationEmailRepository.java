package com.vomatt.repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/** Sign-up invitation emails sent, per sender and address ({@code invitation_emails}). */
@Repository
@RequiredArgsConstructor
public class InvitationEmailRepository {

    private final EntityManager em;

    /** The addresses the sender mailed at or after {@code since}. */
    @SuppressWarnings("unchecked")
    public List<String> findSentSince(UUID senderId, Collection<String> emails, OffsetDateTime since) {
        return em.createNativeQuery("""
                SELECT email FROM vomatt.invitation_emails WHERE sender_id = :senderId AND email IN (:emails) AND sent_at >= :since""")
                .setParameter("senderId", senderId)
                .setParameter("emails", emails)
                .setParameter("since", since)
                .getResultList();
    }

    /** Records the sender mailed the addresses now. */
    public void recordSent(UUID senderId, Collection<String> emails, OffsetDateTime now) {
        em.createNativeQuery("""
                INSERT INTO vomatt.invitation_emails (sender_id, email, sent_at)
                SELECT :senderId, e, :now FROM unnest(CAST(:emails AS text[])) AS e
                ON CONFLICT (sender_id, email) DO UPDATE SET sent_at = EXCLUDED.sent_at""")
                .setParameter("senderId", senderId)
                .setParameter("emails", emails.toArray(String[]::new))
                .setParameter("now", now)
                .executeUpdate();
    }
}
