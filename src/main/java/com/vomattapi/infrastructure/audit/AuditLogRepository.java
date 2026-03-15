package com.vomattapi.infrastructure.audit;

import java.time.LocalDateTime;
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
    
    Page<AuditLog> findByCreatedAtBetweenOrderByCreatedAtDesc(LocalDateTime start, LocalDateTime end, Pageable pageable);
    
    @Query("SELECT al FROM AuditLog al WHERE al.userId = :userId AND al.createdAt >= :since ORDER BY al.createdAt DESC")
    List<AuditLog> findRecentUserActivity(@Param("userId") String userId, @Param("since") LocalDateTime since);
    
    @Query("SELECT COUNT(al) FROM AuditLog al WHERE al.success = false AND al.createdAt >= :since")
    long countFailedActionsLast24Hours(@Param("since") LocalDateTime since);
}