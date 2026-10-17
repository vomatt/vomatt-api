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
 * Notifications are derived on read (ADR 0003); only read state is stored, one row per (user, Poll). A user is notified of
 * a Poll in two ways, one at a time: an Invitation while an Invite-only Poll they are an Invitee of has not Ended, then an
 * Ended Notification once a Poll they own, hold a Ballot in or are invited to has Ended (Polls Closed before they ever
 * opened excluded). Marking read stamps {@code read_at}, so a read Invitation leaves the later Ended Notification unread.
 */
@Repository
@RequiredArgsConstructor
public class NotificationReadRepository {

    // Not Deleted, owned, participated in or invited to (Invitee Polls are a subset, so this indexed semi-join drives
    // the query and the OR below only filters its rows), and either Ended or an Invite-only Poll not yet Ended.
    // end_time > start_time leaves out legacy Cancelled Polls, Closed while Scheduled before ADR 0004, which never opened
    private static final String NOTIFIED_POLLS = """
            FROM vomatt.votes v
            WHERE v.deleted_at IS NULL AND v.id IN %s
              AND (v.end_time <= :now AND v.end_time > v.start_time
                   OR v.end_time > :now AND v.audience = 'INVITE_ONLY'
                      AND EXISTS (SELECT 1 FROM vomatt.vote_invitees i WHERE i.vote_id = v.id AND i.user_id = :userId))
            """.formatted(VoteListRepository.MY_POLL_IDS);

    // A read row covers the Invitation while the Poll has not Ended, and the Ended Notification only once read after the end
    private static final String UNREAD = """
              AND NOT EXISTS (SELECT 1 FROM vomatt.notification_reads r
                              WHERE r.user_id = :userId AND r.vote_id = v.id
                                AND (v.end_time > :now OR r.read_at >= v.end_time))
            """;

    private final EntityManager em;

    // The badge shows "99+" past this, so counting stops once this many unread rows are found.
    // ponytail: bounds the many-unread case only; a user who reads most notifications still scans their whole
    // history (MY_POLL_IDS is materialized). Add a time window or a stored counter if that shows up in slow queries
    public static final int UNREAD_COUNT_CAP = 100;

    /** Unread notifications, at most {@link #UNREAD_COUNT_CAP}. */
    public long countUnread(UUID userId, OffsetDateTime now) {
        Number count = (Number) em.createNativeQuery("SELECT COUNT(*) FROM (SELECT 1 " + NOTIFIED_POLLS + UNREAD
                        + " LIMIT " + UNREAD_COUNT_CAP + ") capped")
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

    /** Marks the Poll's current notification read at {@code now}; idempotent. */
    public void markRead(UUID userId, UUID voteId, OffsetDateTime now) {
        em.createNativeQuery("""
                INSERT INTO vomatt.notification_reads (user_id, vote_id, read_at) VALUES (:userId, :voteId, :now)
                ON CONFLICT (user_id, vote_id) DO UPDATE SET read_at = EXCLUDED.read_at""")
                .setParameter("userId", userId)
                .setParameter("voteId", voteId)
                .setParameter("now", now)
                .executeUpdate();
    }

    /**
     * Binding step 2: forgets the user's read state for the locked Polls whose email Invitee of theirs is about to
     * bind ({@link InviteeRepository#BINDABLE}), so the Invitation reaches them unread like any newly added Invitee.
     */
    public void deleteReadsForEmailBinding(UUID userId, String email, Collection<UUID> voteIds) {
        em.createNativeQuery("""
                DELETE FROM vomatt.notification_reads r
                WHERE r.user_id = :userId AND r.vote_id IN (:voteIds)
                  AND EXISTS (SELECT 1 FROM vomatt.vote_invitees i
                              WHERE i.email = :email AND i.user_id IS NULL AND i.vote_id = r.vote_id AND """
                + InviteeRepository.BINDABLE + ")")
                .setParameter("userId", userId)
                .setParameter("email", email)
                .setParameter("voteIds", voteIds)
                .executeUpdate();
    }

    /** Forgets the users' read state for one Poll, so its current notification is unread again. */
    public void deleteReads(UUID voteId, Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return;
        }
        em.createNativeQuery("DELETE FROM vomatt.notification_reads WHERE vote_id = :voteId AND user_id IN (:userIds)")
                .setParameter("voteId", voteId)
                .setParameter("userIds", userIds)
                .executeUpdate();
    }

    /** Of the given Polls, those whose current notification the user has not read. */
    @SuppressWarnings("unchecked")
    public Set<UUID> findUnreadVoteIds(UUID userId, Collection<UUID> voteIds, OffsetDateTime now) {
        if (voteIds.isEmpty()) {
            return Set.of();
        }
        List<UUID> ids = em.createNativeQuery("SELECT v.id " + NOTIFIED_POLLS + " AND v.id IN (:voteIds)" + UNREAD)
                .setParameter("userId", userId)
                .setParameter("voteIds", voteIds)
                .setParameter("now", now)
                .getResultList();
        return ids.stream().collect(Collectors.toSet());
    }
}
