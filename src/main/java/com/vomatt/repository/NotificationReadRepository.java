package com.vomatt.repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Repository;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/**
 * Ended Notifications are derived on read (ADR 0003): a user's notifications are the Polls they own or hold a
 * Ballot in whose end time has passed, excluding Polls Closed before they ever opened. Only read state is stored.
 */
@Repository
@RequiredArgsConstructor
public class NotificationReadRepository {

    // Ended, opened at some point (a cancelled Scheduled Poll ends before its start time), and owned or participated in
    private static final String NOTIFIED_POLLS = """
            FROM vomatt.votes v
            WHERE v.end_time <= :now AND v.end_time > v.start_time
              AND (v.creator_id = :userId
                   OR EXISTS (SELECT 1 FROM vomatt.user_votes uv WHERE uv.vote_id = v.id AND uv.user_id = :userId))
            """;

    private final EntityManager em;

    public long countUnread(UUID userId, OffsetDateTime now) {
        Number count = (Number) em.createNativeQuery("SELECT COUNT(*) " + NOTIFIED_POLLS + """
                  AND NOT EXISTS (SELECT 1 FROM vomatt.notification_reads r
                                  WHERE r.user_id = :userId AND r.vote_id = v.id)
                """)
                .setParameter("userId", userId)
                .setParameter("now", now)
                .getSingleResult();
        return count.longValue();
    }

    public boolean isNotified(UUID userId, UUID voteId, OffsetDateTime now) {
        return !em.createNativeQuery("SELECT 1 " + NOTIFIED_POLLS + " AND v.id = :voteId")
                .setParameter("userId", userId)
                .setParameter("voteId", voteId)
                .setParameter("now", now)
                .getResultList().isEmpty();
    }

    /** Idempotent: marking an already-read notification changes nothing. */
    public void markRead(UUID userId, UUID voteId) {
        em.createNativeQuery("""
                INSERT INTO vomatt.notification_reads (user_id, vote_id) VALUES (:userId, :voteId)
                ON CONFLICT DO NOTHING""")
                .setParameter("userId", userId)
                .setParameter("voteId", voteId)
                .executeUpdate();
    }

    @SuppressWarnings("unchecked")
    public Set<UUID> findReadVoteIds(UUID userId, Collection<UUID> voteIds) {
        if (voteIds.isEmpty()) {
            return Set.of();
        }
        List<UUID> ids = em.createNativeQuery(
                "SELECT vote_id FROM vomatt.notification_reads WHERE user_id = :userId AND vote_id IN (:voteIds)")
                .setParameter("userId", userId)
                .setParameter("voteIds", voteIds)
                .getResultList();
        return ids.stream().collect(Collectors.toSet());
    }
}
