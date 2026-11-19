package com.vomatt.repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Repository;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

import com.vomatt.admin.dto.InvitationEmailLockResponse;
import com.vomatt.common.util.TimeUtil;

/** Sign-up invitation emails sent per sender and address, and the locks they lead to ({@code invitation_email_limits}). */
@Repository
@RequiredArgsConstructor
public class InvitationEmailRepository {

    private final EntityManager em;

    /**
     * Takes a transaction-scoped advisory lock on each (sender, address) pair, in a fixed order, so concurrent
     * invitations to one address by one sender check and count one after the other.
     */
    public void lockPairs(UUID senderId, Collection<String> emails) {
        em.createNativeQuery("""
                SELECT count(pg_advisory_xact_lock(hashtextextended(CAST(:senderId AS text) || ':' || e, 0)))
                FROM (SELECT e FROM unnest(CAST(:emails AS text[])) AS e ORDER BY e) AS pairs""")
                .setParameter("senderId", senderId)
                .setParameter("emails", emails.toArray(String[]::new))
                .getSingleResult();
    }

    /** Of the addresses, those the sender is locked out of mailing at {@code now}, with when each lock ends. */
    @SuppressWarnings("unchecked")
    public Map<String, OffsetDateTime> findLocked(UUID senderId, Collection<String> emails, OffsetDateTime now) {
        if (emails.isEmpty()) {
            return Map.of();
        }
        List<Object[]> rows = em.createNativeQuery("""
                SELECT email, locked_until FROM vomatt.invitation_email_limits
                WHERE sender_id = :senderId AND email IN (:emails) AND locked_until > :now""")
                .setParameter("senderId", senderId)
                .setParameter("emails", emails)
                .setParameter("now", now)
                .getResultList();
        return rows.stream().collect(Collectors.toMap(r -> (String) r[0], r -> TimeUtil.toOffsetDateTime(r[1])));
    }

    /**
     * Counts one email from the sender to each address now: a window that started at or before {@code windowStart} (or
     * a pair never mailed) starts over at {@code now}, and a window that reaches {@code burstLimit} locks the pair until
     * {@code lockUntil}. Returns the addresses this locked, with when each lock ends.
     */
    @SuppressWarnings("unchecked")
    public Map<String, OffsetDateTime> recordSent(UUID senderId, Collection<String> emails, OffsetDateTime now,
                                                  OffsetDateTime windowStart, int burstLimit, OffsetDateTime lockUntil) {
        String[] addresses = emails.toArray(String[]::new);
        em.createNativeQuery("""
                INSERT INTO vomatt.invitation_email_limits AS l (sender_id, email, window_start, sent_count)
                SELECT :senderId, e, :now, 1 FROM unnest(CAST(:emails AS text[])) AS e
                ON CONFLICT (sender_id, email) DO UPDATE SET
                    window_start = CASE WHEN l.window_start <= :windowStart THEN :now ELSE l.window_start END,
                    sent_count   = CASE WHEN l.window_start <= :windowStart THEN 1 ELSE l.sent_count + 1 END""")
                .setParameter("senderId", senderId)
                .setParameter("emails", addresses)
                .setParameter("now", now)
                .setParameter("windowStart", windowStart)
                .executeUpdate();
        List<Object[]> locked = em.createNativeQuery("""
                UPDATE vomatt.invitation_email_limits SET locked_until = :lockUntil
                WHERE sender_id = :senderId AND email = ANY(CAST(:emails AS text[])) AND sent_count >= :burstLimit
                RETURNING email, locked_until""")
                .setParameter("senderId", senderId)
                .setParameter("emails", addresses)
                .setParameter("burstLimit", burstLimit)
                .setParameter("lockUntil", lockUntil)
                .getResultList();
        return locked.stream().collect(Collectors.toMap(r -> (String) r[0], r -> TimeUtil.toOffsetDateTime(r[1])));
    }

    /** Every lock in force at {@code now}, the latest to end first. */
    @SuppressWarnings("unchecked")
    public List<InvitationEmailLockResponse> findLocks(OffsetDateTime now) {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT l.sender_id, u.username, l.email, l.locked_until
                FROM vomatt.invitation_email_limits l JOIN vomatt.users u ON u.id = l.sender_id
                WHERE l.locked_until > :now ORDER BY l.locked_until DESC, l.sender_id, l.email""")
                .setParameter("now", now)
                .getResultList();
        return rows.stream().map(r -> new InvitationEmailLockResponse(r[0].toString(), (String) r[1], (String) r[2],
                TimeUtil.toOffsetDateTime(r[3]))).toList();
    }

    /**
     * Ends the lock in force on the pair, count and all, so the next email starts a new window; returns whether there
     * was one. (A lock that ran out needs nothing: its window is long over.)
     */
    public boolean unlock(UUID senderId, String email, OffsetDateTime now) {
        return em.createNativeQuery("""
                DELETE FROM vomatt.invitation_email_limits
                WHERE sender_id = :senderId AND email = :email AND locked_until > :now""")
                .setParameter("senderId", senderId)
                .setParameter("email", email)
                .setParameter("now", now)
                .executeUpdate() > 0;
    }
}
