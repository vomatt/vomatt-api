package com.vomatt.common.audit;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {
    
    List<AuditLog> findByUserIdOrderByCreatedAtDesc(String userId);
    
    List<AuditLog> findByActionOrderByCreatedAtDesc(String action);
    
    List<AuditLog> findByResourceTypeAndResourceIdOrderByCreatedAtDesc(String resourceType, String resourceId);
    
    Page<AuditLog> findByCreatedAtBetweenOrderByCreatedAtDesc(OffsetDateTime start, OffsetDateTime end, Pageable pageable);
    
    @Query("SELECT al FROM AuditLog al WHERE al.userId = :userId AND al.createdAt >= :since ORDER BY al.createdAt DESC")
    List<AuditLog> findRecentUserActivity(@Param("userId") String userId, @Param("since") OffsetDateTime since);
    
    @Query("SELECT COUNT(al) FROM AuditLog al WHERE al.success = false AND al.createdAt >= :since")
    long countFailedActionsLast24Hours(@Param("since") OffsetDateTime since);

    /**
     * The one anonymization rule, shared by account deletion, the daily sweep and AuditService.resolveActor: strips who
     * acted (id, username, IP, user agent) from the user's entries, keeping action and resource so admins still see
     * that "a deleted account was suspended". When the entry's resource is the user themself (their own deletion), the
     * resource id and request URI, which spell the id, go too. Entries about the user as a resource by someone else
     * keep their resource_id, which no longer resolves to anyone.
     */
    String ANONYMIZE_SET = """
            SET user_id = NULL, username = NULL, ip_address = NULL, user_agent = NULL,
                resource_id = CASE WHEN lower(resource_id) = lower(user_id) THEN NULL ELSE resource_id END,
                request_uri = CASE WHEN lower(resource_id) = lower(user_id) THEN NULL ELSE request_uri END""";

    /** Account deletion, in the same transaction as the DELETE of the user row. */
    @org.springframework.data.jpa.repository.Modifying
    @Query(value = "UPDATE vomatt.audit_logs " + ANONYMIZE_SET + " WHERE user_id = :userId", nativeQuery = true)
    int anonymizeByUserId(@Param("userId") String userId);

    /**
     * Daily sweep (RetentionJob): entries whose actor no longer exists. Catches what the other two paths cannot see,
     * such as an async entry written while the account's deletion was still uncommitted.
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query(value = "UPDATE vomatt.audit_logs " + ANONYMIZE_SET + " WHERE user_id IS NOT NULL"
            + " AND NOT EXISTS (SELECT 1 FROM vomatt.users u WHERE CAST(u.id AS text) = lower(user_id))", nativeQuery = true)
    int anonymizeOrphanedActors();

    /** Retention: entries older than the cutoff are dropped. */
    @org.springframework.data.jpa.repository.Modifying
    @Query("DELETE FROM AuditLog al WHERE al.createdAt < :cutoff")
    int deleteCreatedBefore(@Param("cutoff") OffsetDateTime cutoff);

    /**
     * Transaction-scoped advisory lock, released at commit: lets one instance run a job while the others skip it.
     * On this repository only because the retention job starts here; the lock itself is per key, not per table.
     */
    @Query(value = "SELECT pg_try_advisory_xact_lock(:key)", nativeQuery = true)
    boolean tryAdvisoryXactLock(@Param("key") long key);
}