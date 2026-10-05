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
     * @param after keyset position (sort key + id) of the previous page's last item, or null
     */
    @SuppressWarnings("unchecked")
    public List<Vote> findPage(VoteListOrder order, String tag, Cursor after, OffsetDateTime now, int limit) {
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
        if (after != null) {
            sql.append(" AND (").append(sortColumn).append(", v.id) ").append(descending ? "<" : ">")
               .append(" (:afterKey, :afterId)");
        }
        String direction = descending ? " DESC" : " ASC";
        sql.append(" ORDER BY ").append(sortColumn).append(direction).append(", v.id").append(direction)
           .append(" LIMIT :limit");

        Query query = em.createNativeQuery(sql.toString(), Vote.class)
                .setParameter("now", now)
                .setParameter("limit", limit);
        if (tag != null) {
            query.setParameter("tag", tag);
        }
        if (after != null) {
            query.setParameter("afterKey", after.timeKey()).setParameter("afterId", after.id());
        }
        return query.getResultList();
    }
}
