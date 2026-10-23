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
     * Account deletion: strips who acted (id, username, IP, user agent) from the user's entries, keeping action and
     * resource so admins still see that "a deleted account was suspended"; entries about the user as a resource keep
     * their resource_id, which no longer resolves to anyone.
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE AuditLog al SET al.userId = NULL, al.username = NULL, al.ipAddress = NULL, al.userAgent = NULL WHERE al.userId = :userId")
    int anonymizeByUserId(@Param("userId") String userId);

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