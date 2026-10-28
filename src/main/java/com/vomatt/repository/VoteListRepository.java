package com.vomatt.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import com.vomatt.common.response.Cursor;
import com.vomatt.common.util.LikePattern;
import com.vomatt.entity.Vote;
import com.vomatt.votes.VoteListOrder;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;

/**
 * Keyset pages of Polls for Feed / Explore / Search / Top (Public Polls only) and My Polls (any Audience, Invite-only ones the user is invited to included). SQL is assembled from constant fragments only;
 * every value is a bound parameter.
 */
@Repository
@RequiredArgsConstructor
public class VoteListRepository {

    /**
     * Ids of Polls the user owns, holds a Ballot in or is invited to. A UNION lets each branch use its own index; an
     * {@code creator_id = :userId OR EXISTS (...)} predicate cannot, and scans every Poll.
     * Shared by the "my Polls" list and Ended Notifications (which add bookmarked Polls); changing it changes both.
     */
    static final String MY_POLL_IDS = """
            (SELECT uv.vote_id FROM vomatt.user_votes uv WHERE uv.user_id = :userId
             UNION
             SELECT c.id FROM vomatt.votes c WHERE c.creator_id = :userId
             UNION
             SELECT i.vote_id FROM vomatt.vote_invitees i WHERE i.user_id = :userId)""";

    /**
     * Poll search on title and description, for a query aliasing votes as {@code v}; bind {@code :q} to the search text
     * and {@code :pattern} to {@link LikePattern#containsPattern}. The bigram GIN index finds candidates (ADR 0001) and
     * LIKE confirms the contiguous match.
     */
    static final String SEARCH_CONDITION = " AND v.search_bigrams @> vomatt.text_bigrams(:q)"
            + " AND lower(COALESCE(v.title, '') || ' ' || COALESCE(v.description, '')) LIKE :pattern ESCAPE '\\'";

    /** The Poll's creator ({@code v}) and the viewer ({@code :viewerId}) have not blocked each other, in either direction. */
    static final String NOT_BLOCKED_EITHER_WAY =
            " AND NOT EXISTS (SELECT 1 FROM vomatt.user_blocks b WHERE b.blocker_id = :viewerId AND b.blocked_id = v.creator_id)"
            + " AND NOT EXISTS (SELECT 1 FROM vomatt.user_blocks b WHERE b.blocker_id = v.creator_id AND b.blocked_id = :viewerId)";

    private final EntityManager em;

    /**
     * @param tag   tag slug filter, or null
     * @param query Search text (already validated, at least two characters), or null
     * @param after keyset position (sort key + id) of the previous page's last item, or null
     * @param viewerId signed-in caller: Polls of users they blocked or who blocked them are left out; null for anonymous callers
     */
    @SuppressWarnings("unchecked")
    public List<Vote> findPage(VoteListOrder order, String tag, String query, Cursor after, OffsetDateTime now,
                               int limit, UUID viewerId) {
        StringBuilder sql = new StringBuilder("SELECT v.* FROM vomatt.votes v WHERE v.deleted_at IS NULL AND v.audience = 'PUBLIC' AND ");
        List<String> sortColumns = switch (order) {
            case NEWEST -> List.of("v.start_time");
            case CLOSING, ENDED -> List.of("v.end_time");
            case TOP -> List.of("v.turnout", "v.start_time");
        };
        boolean descending = order != VoteListOrder.CLOSING;

        sql.append(order == VoteListOrder.ENDED
                ? "v.end_time <= :now"
                : "v.start_time <= :now AND v.end_time > :now");
        if (tag != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM vomatt.vote_tags vt JOIN vomatt.tags t ON t.id = vt.tag_id")
               .append(" WHERE vt.vote_id = v.id AND t.slug = :tag)");
        }
        if (query != null) {
            sql.append(SEARCH_CONDITION);
        }
        if (viewerId != null) {
            sql.append(NOT_BLOCKED_EITHER_WAY);
        }
        if (after != null) {
            sql.append(" AND (").append(String.join(", ", sortColumns)).append(", v.id) ").append(descending ? "<" : ">")
               .append(order == VoteListOrder.TOP ? " (:afterCount, :afterKey, :afterId)" : " (:afterKey, :afterId)");
        }
        String direction = descending ? " DESC" : " ASC";
        sql.append(" ORDER BY ");
        sortColumns.forEach(column -> sql.append(column).append(direction).append(", "));
        sql.append("v.id").append(direction).append(" LIMIT :limit");

        Query nativeQuery = em.createNativeQuery(sql.toString(), Vote.class)
                .setParameter("now", now)
                .setParameter("limit", limit);
        if (tag != null) {
            nativeQuery.setParameter("tag", tag);
        }
        if (query != null) {
            nativeQuery.setParameter("q", query)
                    .setParameter("pattern", LikePattern.containsPattern(query));
        }
        if (viewerId != null) {
            nativeQuery.setParameter("viewerId", viewerId);
        }
        if (after != null && order == VoteListOrder.TOP) {
            nativeQuery.setParameter("afterCount", after.countKey())
                    .setParameter("afterKey", after.compoundTimeKey()).setParameter("afterId", after.id());
        } else if (after != null) {
            nativeQuery.setParameter("afterKey", after.timeKey()).setParameter("afterId", after.id());
        }
        return nativeQuery.getResultList();
    }

    /**
     * Following list: Open Public Polls created by users the follower follows or carrying a tag the follower follows,
     * most recently opened first, each once. Nothing from the follower themselves, a suspended creator, or a creator
     * either side blocked. The user branch starts from the follower's follows and keeps at most a page per followed user
     * (read through idx_votes_creator_start_id); the tag branch reads only Polls not yet Ended (idx_votes_end_id; OFFSET 0
     * keeps the planner from walking idx_votes_start_id through Ended history instead) and probes each one's tags by primary
     * key (OFFSET 0 in the EXISTS stops a hash over every row of the followed tags).
     * Both filter before their LIMIT, so filtered rows never shorten a page; IN drops duplicates.
     */
    @SuppressWarnings("unchecked")
    public List<Vote> findFollowing(UUID followerId, Cursor after, OffsetDateTime now, int limit) {
        // ponytail: the user branch walks each followed user's Polls past Ended ones, and the tag branch checks every
        // not-yet-Ended Poll per page; add Open-only partial indexes if creators pile up history or live Polls grow large
        String open = """
                v.deleted_at IS NULL AND v.audience = 'PUBLIC' AND v.start_time <= :now AND v.end_time > :now"""
                + (after == null ? "" : " AND (v.start_time, v.id) < (:afterKey, :afterId)")
                + " AND EXISTS (SELECT 1 FROM vomatt.users u WHERE u.id = v.creator_id AND u.active)" + NOT_BLOCKED_EITHER_WAY;
        String sql = """
                SELECT v.* FROM vomatt.votes v WHERE v.id IN (
                    SELECT fv.id FROM vomatt.user_follows f
                    CROSS JOIN LATERAL (
                        SELECT v.id FROM vomatt.votes v
                        WHERE v.creator_id = f.followed_id AND\s""" + open + """

                        ORDER BY v.start_time DESC, v.id DESC LIMIT :limit) fv
                    WHERE f.follower_id = :viewerId
                    UNION ALL
                    (SELECT tv.id FROM (
                        SELECT v.id, v.start_time FROM vomatt.votes v
                        WHERE v.creator_id <> :viewerId AND\s""" + open + """

                          AND EXISTS (SELECT 1 FROM vomatt.vote_tags vt JOIN vomatt.tag_follows tf ON tf.tag_id = vt.tag_id
                                      WHERE vt.vote_id = v.id AND tf.user_id = :viewerId OFFSET 0)
                        OFFSET 0) tv
                     ORDER BY tv.start_time DESC, tv.id DESC LIMIT :limit))
                ORDER BY v.start_time DESC, v.id DESC LIMIT :limit""";
        Query nativeQuery = em.createNativeQuery(sql, Vote.class)
                .setParameter("viewerId", followerId)
                .setParameter("now", now)
                .setParameter("limit", limit);
        if (after != null) {
            nativeQuery.setParameter("afterKey", after.timeKey()).setParameter("afterId", after.id());
        }
        return nativeQuery.getResultList();
    }

    /**
     * My Polls: Polls the user created, holds a Ballot in or is invited to, minus those of users who blocked the user.
     * Not yet Ended (Scheduled + Open) closing soonest first, or Ended most recently ended first.
     */
    @SuppressWarnings("unchecked")
    public List<Vote> findMine(UUID userId, boolean ended, Cursor after, OffsetDateTime now, int limit) {
        String direction = ended ? " DESC" : " ASC";
        StringBuilder sql = new StringBuilder("SELECT v.* FROM vomatt.votes v WHERE v.deleted_at IS NULL AND v.id IN ")
                .append(MY_POLL_IDS);
        sql.append(ended ? " AND v.end_time <= :now" : " AND v.end_time > :now");
        sql.append(" AND NOT EXISTS (SELECT 1 FROM vomatt.user_blocks b WHERE b.blocker_id = v.creator_id AND b.blocked_id = :userId)");
        if (after != null) {
            sql.append(" AND (v.end_time, v.id) ").append(ended ? "<" : ">").append(" (:afterKey, :afterId)");
        }
        sql.append(" ORDER BY v.end_time").append(direction).append(", v.id").append(direction).append(" LIMIT :limit");

        Query nativeQuery = em.createNativeQuery(sql.toString(), Vote.class)
                .setParameter("userId", userId)
                .setParameter("now", now)
                .setParameter("limit", limit);
        if (after != null) {
            nativeQuery.setParameter("afterKey", after.timeKey()).setParameter("afterId", after.id());
        }
        return nativeQuery.getResultList();
    }

    /**
     * A user's public profile list: Public Polls they created that have opened (Open or Ended, not Scheduled and not
     * Cancelled before opening), most recently opened first.
     */
    @SuppressWarnings("unchecked")
    public List<Vote> findByCreator(UUID creatorId, Cursor after, OffsetDateTime now, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT v.* FROM vomatt.votes v
                WHERE v.creator_id = :creatorId AND v.start_time <= :now AND v.end_time > v.start_time
                  AND v.deleted_at IS NULL AND v.audience = 'PUBLIC'
                """);
        if (after != null) {
            sql.append(" AND (v.start_time, v.id) < (:afterKey, :afterId)");
        }
        sql.append(" ORDER BY v.start_time DESC, v.id DESC LIMIT :limit");

        Query nativeQuery = em.createNativeQuery(sql.toString(), Vote.class)
                .setParameter("creatorId", creatorId)
                .setParameter("now", now)
                .setParameter("limit", limit);
        if (after != null) {
            nativeQuery.setParameter("afterKey", after.timeKey()).setParameter("afterId", after.id());
        }
        return nativeQuery.getResultList();
    }
}
