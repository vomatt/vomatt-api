package com.vomatt.repository;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.stereotype.Repository;

import com.vomatt.common.response.Cursor;
import com.vomatt.entity.Vote;
import com.vomatt.votes.VoteListOrder;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;

/**
 * Keyset pages of Polls for Feed / Explore / Search. SQL is assembled from constant fragments only;
 * every value is a bound parameter.
 */
@Repository
@RequiredArgsConstructor
public class VoteListRepository {

    private final EntityManager em;

    /**
     * @param tag   tag slug filter, or null
     * @param query Search text (already validated, at least two characters), or null
     * @param after keyset position (sort key + id) of the previous page's last item, or null
     */
    @SuppressWarnings("unchecked")
    public List<Vote> findPage(VoteListOrder order, String tag, String query, Cursor after, OffsetDateTime now,
                               int limit) {
        StringBuilder sql = new StringBuilder("SELECT v.* FROM vomatt.votes v WHERE ");
        String sortColumn = switch (order) {
            case NEWEST -> "v.start_time";
            case CLOSING, ENDED -> "v.end_time";
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
            // bigram GIN index finds candidates (ADR 0001); LIKE confirms the contiguous match
            sql.append(" AND v.search_bigrams @> vomatt.text_bigrams(:q)")
               .append(" AND lower(COALESCE(v.title, '') || ' ' || COALESCE(v.description, '')) LIKE :pattern ESCAPE '\\'");
        }
        if (after != null) {
            sql.append(" AND (").append(sortColumn).append(", v.id) ").append(descending ? "<" : ">")
               .append(" (:afterKey, :afterId)");
        }
        String direction = descending ? " DESC" : " ASC";
        sql.append(" ORDER BY ").append(sortColumn).append(direction).append(", v.id").append(direction)
           .append(" LIMIT :limit");

        Query nativeQuery = em.createNativeQuery(sql.toString(), Vote.class)
                .setParameter("now", now)
                .setParameter("limit", limit);
        if (tag != null) {
            nativeQuery.setParameter("tag", tag);
        }
        if (query != null) {
            nativeQuery.setParameter("q", query)
                    .setParameter("pattern", "%" + escapeLike(query.toLowerCase()) + "%");
        }
        if (after != null) {
            nativeQuery.setParameter("afterKey", after.timeKey()).setParameter("afterId", after.id());
        }
        return nativeQuery.getResultList();
    }

    // % and _ in the user's query are literal characters, not wildcards
    static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
