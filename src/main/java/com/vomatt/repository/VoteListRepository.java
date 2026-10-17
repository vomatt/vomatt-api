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
     * Shared by the "my Polls" list and Ended Notifications; changing it changes both.
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

    private final EntityManager em;

    /**
     * @param tag   tag slug filter, or null
     * @param query Search text (already validated, at least two characters), or null
     * @param after keyset position (sort key + id) of the previous page's last item, or null
     */
    @SuppressWarnings("unchecked")
    public List<Vote> findPage(VoteListOrder order, String tag, String query, Cursor after, OffsetDateTime now,
                               int limit) {
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
        if (after != null && order == VoteListOrder.TOP) {
            nativeQuery.setParameter("afterCount", after.countKey())
                    .setParameter("afterKey", after.compoundTimeKey()).setParameter("afterId", after.id());
        } else if (after != null) {
            nativeQuery.setParameter("afterKey", after.timeKey()).setParameter("afterId", after.id());
        }
        return nativeQuery.getResultList();
    }

    /**
     * My Polls: Polls the user created or holds a Ballot in. Not yet Ended (Scheduled + Open) closing soonest
     * first, or Ended most recently ended first.
     */
    @SuppressWarnings("unchecked")
    public List<Vote> findMine(UUID userId, boolean ended, Cursor after, OffsetDateTime now, int limit) {
        String direction = ended ? " DESC" : " ASC";
        StringBuilder sql = new StringBuilder("SELECT v.* FROM vomatt.votes v WHERE v.deleted_at IS NULL AND v.id IN ")
                .append(MY_POLL_IDS);
        sql.append(ended ? " AND v.end_time <= :now" : " AND v.end_time > :now");
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
