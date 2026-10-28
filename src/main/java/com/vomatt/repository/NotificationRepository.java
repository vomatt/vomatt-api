package com.vomatt.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Repository;

import com.vomatt.common.response.Cursor;
import com.vomatt.entity.NotificationKind;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;

/**
 * The notification feed, derived on read from the tables the events already live in (ADR 0003, ADR 0005). Each kind is
 * one SQL branch yielding {@code (kind, read_kind, source_id, created_at, actor_id, vote_id, comment_id)}; the feed, the unread
 * count, the existence check and My Polls' {@code unread} all run these branches, so they agree on what a notification
 * is. Only read state is stored: a row per read notification in {@code notification_reads}, and a per-user moment up to
 * which everything is read in {@code notification_read_all}. SQL is assembled from constant fragments only; every value
 * is a bound parameter.
 */
@Repository
@RequiredArgsConstructor
public class NotificationRepository {

    /** The badge shows "99+" past this, so counting stops once this many unread notifications are found. */
    public static final int UNREAD_COUNT_CAP = 100;

    /** How long before its end an Open Poll gives a CLOSING_SOON notification. */
    private static final int CLOSING_SOON_HOURS = 24;

    private static final String NONE = "CAST(NULL AS uuid)";

    // Not a Reply in a thread started by a user the user blocked; for a branch over vote_comments c
    private static final String NOT_IN_BLOCKED_THREAD = " AND NOT EXISTS (SELECT 1 FROM vomatt.vote_comments tp"
            + " JOIN vomatt.user_blocks x ON x.blocker_id = :userId AND x.blocked_id = tp.user_id WHERE tp.id = c.parent_id)";

    // Live Comment of someone else, on a Poll the user can still see, outside threads started by a user the user blocked
    // (the lists hide those threads, VoteCommentRepository.isHiddenFrom); for a branch over vote_comments c and votes v
    private static final String COMMENT_VISIBLE = " AND c.is_deleted = FALSE AND c.user_id <> :userId AND v.deleted_at IS NULL"
            + NOT_IN_BLOCKED_THREAD
            + " AND (v.audience <> 'INVITE_ONLY' OR v.creator_id = :userId"
            + " OR EXISTS (SELECT 1 FROM vomatt.vote_invitees vi WHERE vi.vote_id = v.id AND vi.user_id = :userId))";

    // Ids of Comments on the user's Polls and Replies to the user's live Comments
    private static final String COMMENT_IDS = """
            SELECT oc.id FROM vomatt.votes o JOIN vomatt.vote_comments oc ON oc.vote_id = o.id
            WHERE o.creator_id = :userId
            UNION
            SELECT r.id FROM vomatt.vote_comments p JOIN vomatt.vote_comments r ON r.parent_id = p.id
            WHERE p.user_id = :userId AND p.is_deleted = FALSE""";

    private static String select(NotificationKind kind, String sourceId, String createdAt, String actorId, String voteId,
                                 String commentId) {
        return ("SELECT CAST('%s' AS text) AS kind, CAST('%s' AS text) AS read_kind, %s AS source_id, %s AS created_at,"
                + " %s AS actor_id, %s AS vote_id, %s AS comment_id ")
                .formatted(kind.name(), readKind(kind), sourceId, createdAt, actorId, voteId, commentId);
    }

    /**
     * The kind a notification's read state is stored under. A Comment is listed as COMMENT or MENTION depending on the
     * COMMENT switch, so both share COMMENT's read state.
     */
    private static String readKind(NotificationKind kind) {
        return (kind == NotificationKind.MENTION ? NotificationKind.COMMENT : kind).name();
    }

    // Branches bind :userId, and :now and :soon where they use them. A row's created_at does not change while it is
    // listed and is at most :now when it joins the feed, which keeps the (created_at, source_id) keyset sound
    private static final Map<NotificationKind, String> BRANCHES = new EnumMap<>(Map.of(
            // Ended Polls the user owns, holds a Ballot in, is invited to or bookmarked before they Ended, once each (the
            // arms' rows are equal, so UNION merges them); Polls Cancelled before they opened are CANCELLED. A bookmarked
            // Invite-only Poll counts only through ownership or invitation, so a removed Invitee's bookmark gives nothing
            NotificationKind.RESULT, select(NotificationKind.RESULT, "v.id", "v.end_time", NONE, "v.id", NONE) + """
                    FROM vomatt.votes v
                    WHERE v.deleted_at IS NULL AND v.end_time <= :now AND v.end_time > v.start_time AND v.id IN %s
                    UNION
                    """.formatted(VoteListRepository.MY_POLL_IDS)
                    + select(NotificationKind.RESULT, "v.id", "v.end_time", NONE, "v.id", NONE) + """
                    FROM vomatt.vote_bookmarks bm JOIN vomatt.votes v ON v.id = bm.vote_id
                    WHERE bm.user_id = :userId AND bm.created_at < v.end_time AND v.audience <> 'INVITE_ONLY'
                      AND v.deleted_at IS NULL AND v.end_time <= :now AND v.end_time > v.start_time""",
            NotificationKind.CANCELLED, select(NotificationKind.CANCELLED, "v.id", "v.end_time", "v.creator_id", "v.id", NONE) + """
                    FROM vomatt.vote_invitees i JOIN vomatt.votes v ON v.id = i.vote_id
                    WHERE i.user_id = :userId AND v.deleted_at IS NULL AND v.audience = 'INVITE_ONLY'
                      AND v.end_time <= :now AND v.end_time <= v.start_time""",
            // Until the Poll Ends; its RESULT takes over then. updated_at is when the user was invited, or when their
            // email Invitee bound to the account. A removed and re-added Invitee is a new row, so a new, unread notification
            NotificationKind.INVITATION, select(NotificationKind.INVITATION, "i.id", "i.updated_at", "v.creator_id", "v.id", NONE) + """
                    FROM vomatt.vote_invitees i JOIN vomatt.votes v ON v.id = i.vote_id
                    WHERE i.user_id = :userId AND v.deleted_at IS NULL AND v.audience = 'INVITE_ONLY' AND v.end_time > :now""",
            NotificationKind.FOLLOWED, select(NotificationKind.FOLLOWED, "f.id", "f.created_at", "f.follower_id", NONE, NONE) + """
                    FROM vomatt.user_follows f WHERE f.followed_id = :userId""",
            // Others' Comments and Replies on the user's Polls, and others' Replies to the user's live Comments
            NotificationKind.COMMENT, select(NotificationKind.COMMENT, "c.id", "c.created_at", "c.user_id", "v.id", "c.id")
                    + "FROM vomatt.vote_comments c JOIN vomatt.votes v ON v.id = c.vote_id WHERE c.id IN ("
                    + COMMENT_IDS + ")" + COMMENT_VISIBLE,
            // Others' Comments and Replies that @mention the user. With COMMENT on, branch() also drops the user's
            // COMMENTs (MENTION_NOT_COMMENT)
            NotificationKind.MENTION, select(NotificationKind.MENTION, "c.id", "c.created_at", "c.user_id", "v.id", "c.id")
                    + "FROM vomatt.vote_comments c JOIN vomatt.votes v ON v.id = c.vote_id"
                    + " WHERE c.mentioned_user_ids @> ARRAY[CAST(:userId AS uuid)]" + COMMENT_VISIBLE,
            // Open Polls ending within the window that the user can cast a Ballot in and has not: Invite-only ones they
            // are invited to, Public ones of users they follow. Listed from when the window opened (or the Poll did)
            NotificationKind.CLOSING_SOON, select(NotificationKind.CLOSING_SOON, "v.id",
                    "GREATEST(v.start_time, v.end_time - INTERVAL '" + CLOSING_SOON_HOURS + " hours')", "v.creator_id", "v.id", NONE) + """
                    FROM vomatt.votes v
                    WHERE v.id IN (SELECT i.vote_id FROM vomatt.vote_invitees i JOIN vomatt.votes iv ON iv.id = i.vote_id
                                   WHERE i.user_id = :userId AND iv.audience = 'INVITE_ONLY'
                                   UNION
                                   SELECT fv.id FROM vomatt.user_follows f JOIN vomatt.votes fv ON fv.creator_id = f.followed_id
                                   WHERE f.follower_id = :userId AND fv.audience = 'PUBLIC'
                                     AND fv.end_time > :now AND fv.end_time <= :soon)
                      AND v.deleted_at IS NULL AND v.start_time <= :now AND v.end_time > :now AND v.end_time <= :soon
                      AND v.creator_id <> :userId
                      AND NOT EXISTS (SELECT 1 FROM vomatt.user_votes uv WHERE uv.vote_id = v.id AND uv.user_id = :userId)"""));

    // Inside each branch, before its LIMIT: nothing from a suspended user, nor from someone the user blocked or who
    // blocked the user
    private static final String ACTOR_ALLOWED = " (b.actor_id IS NULL OR " + UserFilters.active("b.actor_id")
            + " AND " + UserFilters.notBlockedEitherWay("b.actor_id", ":userId") + ")";

    // Inside each branch, next to ACTOR_ALLOWED: nothing about a Poll whose owner blocked the user, who cannot reach it
    private static final String POLL_ALLOWED = """
             AND (b.vote_id IS NULL
                  OR NOT EXISTS (SELECT 1 FROM vomatt.votes pv
                                 JOIN vomatt.user_blocks x ON x.blocker_id = pv.creator_id AND x.blocked_id = :userId
                                 WHERE pv.id = b.vote_id))""";

    // Appended to MENTION when COMMENT is on too: a Comment that is the user's COMMENT is not also their MENTION, so one
    // Comment gives one notification
    private static final String MENTION_NOT_COMMENT =
            " AND NOT EXISTS (SELECT 1 FROM (" + COMMENT_IDS + ") cm WHERE cm.id = c.id)";

    private static final String READ = """
            (n.created_at <= COALESCE((SELECT ra.read_at FROM vomatt.notification_read_all ra WHERE ra.user_id = :userId),
                                      TIMESTAMPTZ 'epoch')
             OR EXISTS (SELECT 1 FROM vomatt.notification_reads r
                        WHERE r.user_id = :userId AND r.kind = n.read_kind AND r.source_id = n.source_id))""";

    private static final String ROW = "SELECT n.kind, n.source_id, n.created_at, n.actor_id, n.vote_id, n.comment_id, "
            + READ + " AS is_read FROM (";

    private static final Set<NotificationKind> POLL_KINDS = Set.of(NotificationKind.INVITATION, NotificationKind.RESULT);

    private final EntityManager em;

    /**
     * One page of the feed, newest first, ordered by (created_at, source_id).
     *
     * @param after keyset position (created_at + source id) of the previous page's last item, or null
     */
    @SuppressWarnings("unchecked")
    public List<NotificationRow> findPage(UUID userId, Set<NotificationKind> kinds, Cursor after, OffsetDateTime now,
                                          int limit) {
        String keyset = after == null ? "" : " AND (b.created_at, b.source_id) < (:afterTime, :afterId)";
        String sql = ROW + union(kinds, kinds, keyset + " ORDER BY b.created_at DESC, b.source_id DESC LIMIT :limit")
                + ") n ORDER BY n.created_at DESC, n.source_id DESC LIMIT :limit";
        Query query = bind(sql, userId, now).setParameter("limit", limit);
        if (after != null) {
            query.setParameter("afterTime", after.timeKey()).setParameter("afterId", after.id());
        }
        return ((List<Object[]>) query.getResultList()).stream().map(NotificationRow::of).toList();
    }

    /** Unread notifications of the given kinds, at most {@link #UNREAD_COUNT_CAP}. */
    public long countUnread(UUID userId, Set<NotificationKind> kinds, OffsetDateTime now) {
        // ponytail: RESULT materializes every Poll the user ever took part in; add a time window or a stored counter
        // if this shows up in slow queries
        String sql = "SELECT COUNT(*) FROM (SELECT 1 FROM (" + union(kinds, kinds, "") + ") n WHERE NOT " + READ
                + " LIMIT " + UNREAD_COUNT_CAP + ") capped";
        return ((Number) bind(sql, userId, now).getSingleResult()).longValue();
    }

    /**
     * Whether the user has this notification now, of a kind on or off; {@code enabled} are the kinds their preferences
     * leave on, for the MENTION-or-COMMENT choice the feed makes.
     */
    public boolean exists(UUID userId, NotificationKind kind, UUID sourceId, Set<NotificationKind> enabled,
                          OffsetDateTime now) {
        String sql = "SELECT 1 FROM (" + union(Set.of(kind), enabled, " AND b.source_id = :sourceId") + ") n";
        return !bind(sql, userId, now).setParameter("sourceId", sourceId).getResultList().isEmpty();
    }

    /**
     * The Poll's current notification for the user, as the deprecated Poll-id read and My Polls know it: the
     * Invitation while the Poll has not Ended, then its Result.
     */
    @SuppressWarnings("unchecked")
    public Optional<NotificationRow> findCurrentForPoll(UUID userId, UUID voteId, OffsetDateTime now) {
        String sql = ROW + union(POLL_KINDS, POLL_KINDS, " AND b.vote_id = :voteId") + ") n";
        List<Object[]> rows = bind(sql, userId, now).setParameter("voteId", voteId).getResultList();
        return rows.stream().map(NotificationRow::of).findFirst();
    }

    /** Of the given Polls, those whose current notification (Invitation, then Result) the user has not read. */
    @SuppressWarnings("unchecked")
    public Set<UUID> findUnreadVoteIds(UUID userId, Collection<UUID> voteIds, OffsetDateTime now) {
        if (voteIds.isEmpty()) {
            return Set.of();
        }
        String sql = "SELECT n.vote_id FROM (" + union(POLL_KINDS, POLL_KINDS, " AND b.vote_id IN (:voteIds)") + ") n WHERE NOT " + READ;
        List<UUID> ids = bind(sql, userId, now).setParameter("voteIds", voteIds).getResultList();
        return Set.copyOf(ids);
    }

    /** Marks one notification read, under its {@link #readKind}; idempotent. */
    public void markRead(UUID userId, NotificationKind kind, UUID sourceId, OffsetDateTime now) {
        em.createNativeQuery("""
                INSERT INTO vomatt.notification_reads (user_id, kind, source_id, read_at) VALUES (:userId, :kind, :sourceId, :now)
                ON CONFLICT (user_id, kind, source_id) DO NOTHING""")
                .setParameter("userId", userId)
                .setParameter("kind", readKind(kind))
                .setParameter("sourceId", sourceId)
                .setParameter("now", now)
                .executeUpdate();
    }

    /** Marks every notification created up to {@code now} read; later ones arrive unread. Never moves backwards. */
    public void markAllRead(UUID userId, OffsetDateTime now) {
        em.createNativeQuery("""
                INSERT INTO vomatt.notification_read_all (user_id, read_at) VALUES (:userId, :now)
                ON CONFLICT (user_id) DO UPDATE SET read_at = GREATEST(vomatt.notification_read_all.read_at, EXCLUDED.read_at)""")
                .setParameter("userId", userId)
                .setParameter("now", now)
                .executeUpdate();
    }

    /**
     * The given kinds' branches, each filtered by actor and Poll owner and then by {@code tail}, as one UNION ALL;
     * {@code enabled} picks between MENTION and COMMENT as a feed of those kinds does.
     */
    private static String union(Set<NotificationKind> kinds, Set<NotificationKind> enabled, String tail) {
        return kinds.stream()
                .map(kind -> "(SELECT * FROM (" + branch(kind, enabled) + ") b WHERE" + ACTOR_ALLOWED + POLL_ALLOWED + tail + ")")
                .collect(Collectors.joining(" UNION ALL "));
    }

    private static String branch(NotificationKind kind, Set<NotificationKind> enabled) {
        return kind == NotificationKind.MENTION && enabled.contains(NotificationKind.COMMENT)
                ? BRANCHES.get(kind) + MENTION_NOT_COMMENT : BRANCHES.get(kind);
    }

    // Branches bind only the times they use (FOLLOWED alone uses none)
    private Query bind(String sql, UUID userId, OffsetDateTime now) {
        Query query = em.createNativeQuery(sql).setParameter("userId", userId);
        if (sql.contains(":now")) {
            query.setParameter("now", now);
        }
        if (sql.contains(":soon")) {
            query.setParameter("soon", now.plusHours(CLOSING_SOON_HOURS));
        }
        return query;
    }

    /** One derived notification; {@code actorId}, {@code voteId} and {@code commentId} are null when the kind has none. */
    public record NotificationRow(NotificationKind kind, UUID sourceId, OffsetDateTime createdAt, UUID actorId,
                                  UUID voteId, UUID commentId, boolean read) {

        static NotificationRow of(Object[] row) {
            return new NotificationRow(NotificationKind.valueOf((String) row[0]), (UUID) row[1], toOffsetDateTime(row[2]),
                    (UUID) row[3], (UUID) row[4], (UUID) row[5], (Boolean) row[6]);
        }

        private static OffsetDateTime toOffsetDateTime(Object value) {
            return switch (value) {
                case OffsetDateTime time -> time;
                case Instant instant -> instant.atOffset(ZoneOffset.UTC);
                case Timestamp timestamp -> timestamp.toInstant().atOffset(ZoneOffset.UTC);
                default -> throw new IllegalStateException("Unexpected timestamp type " + value.getClass());
            };
        }
    }
}
