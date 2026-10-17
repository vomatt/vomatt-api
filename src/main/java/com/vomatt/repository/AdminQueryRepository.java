package com.vomatt.repository;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import com.vomatt.admin.dto.AdminStatsResponse;
import com.vomatt.common.audit.AuditLog;
import com.vomatt.common.util.LikePattern;
import com.vomatt.entity.Audience;
import com.vomatt.entity.User;
import com.vomatt.entity.Vote;
import com.vomatt.entity.VoteComment;
import com.vomatt.entity.VoteStatus;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;

/**
 * Offset pages for the admin back office, newest first, with every filter optional. Unlike the public lists they
 * include suspended users, non-Public and Deleted Polls, and deleted Comments. SQL is assembled from constant
 * fragments only; every value is a bound parameter.
 */
@Repository
@RequiredArgsConstructor
public class AdminQueryRepository {

    private final EntityManager em;

    /**
     * @param q      matched case-insensitively (contains) against username, email, phone number and display name, or null
     * @param active true for active, false for suspended, null for both
     * @param role   one of the roles, or null
     */
    // ponytail: LIKE over four columns scans every user; add pg_trgm indexes if the user table outgrows it
    public Page<User> findUsers(String q, Boolean active, String role, Pageable pageable) {
        StringBuilder sql = new StringBuilder("FROM vomatt.users u WHERE true");
        Map<String, Object> params = new HashMap<>();
        if (q != null) {
            sql.append(" AND (lower(u.username) LIKE :pattern ESCAPE '\\' OR lower(u.email) LIKE :pattern ESCAPE '\\'")
               .append(" OR u.phone_number LIKE :pattern ESCAPE '\\' OR lower(u.display_name) LIKE :pattern ESCAPE '\\')");
            params.put("pattern", LikePattern.containsPattern(q));
        }
        if (active != null) {
            sql.append(" AND u.active = :active");
            params.put("active", active);
        }
        if (role != null) {
            sql.append(" AND CAST(:role AS text) = ANY(u.roles)");
            params.put("role", role);
        }
        return page("SELECT u.*", sql, params, "u.created_at DESC, u.id DESC", User.class, pageable);
    }

    /**
     * @param q       Search text on title and description (already validated, at least two characters), or null
     * @param status  derived from time as {@link Vote#statusAt}: Ended wins over Scheduled; null for any
     * @param deleted true for Deleted only, false for not Deleted only, null for both
     */
    public Page<Vote> findVotes(String q, VoteStatus status, UUID creatorId, Audience audience, Boolean deleted,
                                OffsetDateTime now, Pageable pageable) {
        StringBuilder sql = new StringBuilder("FROM vomatt.votes v WHERE true");
        Map<String, Object> params = new HashMap<>();
        if (q != null) {
            // bigram GIN index finds candidates (ADR 0001); LIKE confirms the contiguous match
            sql.append(" AND v.search_bigrams @> vomatt.text_bigrams(:q)")
               .append(" AND lower(COALESCE(v.title, '') || ' ' || COALESCE(v.description, '')) LIKE :pattern ESCAPE '\\'");
            params.put("q", q);
            params.put("pattern", LikePattern.containsPattern(q));
        }
        if (status != null) {
            sql.append(switch (status) {
                case SCHEDULED -> " AND v.start_time > :now AND v.end_time > :now";
                case OPEN -> " AND v.start_time <= :now AND v.end_time > :now";
                case ENDED -> " AND v.end_time <= :now";
            });
            params.put("now", now);
        }
        if (creatorId != null) {
            sql.append(" AND v.creator_id = :creatorId");
            params.put("creatorId", creatorId);
        }
        if (audience != null) {
            sql.append(" AND v.audience = :audience");
            params.put("audience", audience.name());
        }
        if (deleted != null) {
            sql.append(deleted ? " AND v.deleted_at IS NOT NULL" : " AND v.deleted_at IS NULL");
        }
        return page("SELECT v.*", sql, params, "v.created_at DESC, v.id DESC", Vote.class, pageable);
    }

    /**
     * @param q       matched case-insensitively (contains) against the content, or null
     * @param deleted true for deleted only, false for live only, null for both
     */
    // ponytail: content LIKE scans the Poll's (or every) Comment; fine for moderation volumes
    public Page<VoteComment> findComments(UUID voteId, UUID userId, String q, Boolean deleted, Pageable pageable) {
        StringBuilder sql = new StringBuilder("FROM vomatt.vote_comments c WHERE true");
        Map<String, Object> params = new HashMap<>();
        if (voteId != null) {
            sql.append(" AND c.vote_id = :voteId");
            params.put("voteId", voteId);
        }
        if (userId != null) {
            sql.append(" AND c.user_id = :userId");
            params.put("userId", userId);
        }
        if (q != null) {
            sql.append(" AND lower(c.content) LIKE :pattern ESCAPE '\\'");
            params.put("pattern", LikePattern.containsPattern(q));
        }
        if (deleted != null) {
            sql.append(" AND c.is_deleted = :deleted");
            params.put("deleted", deleted);
        }
        return page("SELECT c.*", sql, params, "c.created_at DESC, c.id DESC", VoteComment.class, pageable);
    }

    /** @param from inclusive lower bound on created_at, or null; {@code to} is the exclusive upper bound, or null */
    public Page<AuditLog> findAuditLogs(UUID userId, String action, String resourceType, UUID resourceId,
                                        OffsetDateTime from, OffsetDateTime to, Pageable pageable) {
        StringBuilder sql = new StringBuilder("FROM vomatt.audit_logs a WHERE true");
        Map<String, Object> params = new HashMap<>();
        if (userId != null) {
            sql.append(" AND a.user_id = :userId");
            params.put("userId", userId.toString());
        }
        if (action != null) {
            sql.append(" AND a.action = :action");
            params.put("action", action);
        }
        if (resourceType != null) {
            sql.append(" AND a.resource_type = :resourceType");
            params.put("resourceType", resourceType);
        }
        if (resourceId != null) {
            sql.append(" AND a.resource_id = :resourceId");
            params.put("resourceId", resourceId.toString());
        }
        if (from != null) {
            sql.append(" AND a.created_at >= :from");
            params.put("from", from);
        }
        if (to != null) {
            sql.append(" AND a.created_at < :to");
            params.put("to", to);
        }
        return page("SELECT a.*", sql, params, "a.created_at DESC, a.id DESC", AuditLog.class, pageable);
    }

    /** Dashboard counts in one round trip; Poll status is derived from time, Deleted Polls count only as deleted. */
    public AdminStatsResponse stats(OffsetDateTime now) {
        Object[] row = (Object[]) em.createNativeQuery("""
                SELECT (SELECT count(*) FROM vomatt.users),
                       (SELECT count(*) FROM vomatt.users WHERE created_at >= :weekAgo),
                       (SELECT count(*) FROM vomatt.users WHERE active = false),
                       (SELECT count(*) FROM vomatt.votes WHERE deleted_at IS NULL AND start_time <= :now AND end_time > :now),
                       (SELECT count(*) FROM vomatt.votes WHERE deleted_at IS NULL AND end_time <= :now),
                       (SELECT count(*) FROM vomatt.votes WHERE deleted_at IS NOT NULL),
                       (SELECT count(*) FROM vomatt.user_votes WHERE created_at >= :dayAgo)""")
                .setParameter("now", now)
                .setParameter("weekAgo", now.minusDays(7))
                .setParameter("dayAgo", now.minusHours(24))
                .getSingleResult();
        return new AdminStatsResponse(count(row[0]), count(row[1]), count(row[2]), count(row[3]), count(row[4]),
                count(row[5]), count(row[6]));
    }

    // Count and page share one WHERE clause and parameter set, so total always matches the rows
    @SuppressWarnings("unchecked")
    private <T> Page<T> page(String select, StringBuilder fromWhere, Map<String, Object> params, String orderBy,
                             Class<T> type, Pageable pageable) {
        Query count = em.createNativeQuery("SELECT count(*) " + fromWhere);
        Query rows = em.createNativeQuery(select + " " + fromWhere + " ORDER BY " + orderBy + " LIMIT :limit OFFSET :offset", type)
                .setParameter("limit", pageable.getPageSize())
                .setParameter("offset", pageable.getOffset());
        params.forEach((name, value) -> {
            count.setParameter(name, value);
            rows.setParameter(name, value);
        });
        return new PageImpl<>(rows.getResultList(), pageable, count(count.getSingleResult()));
    }

    private static long count(Object value) {
        return ((Number) value).longValue();
    }
}
