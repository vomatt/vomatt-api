package com.vomatt.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.vomatt.entity.UserBlock;

@Repository
public interface UserBlockRepository extends JpaRepository<UserBlock, UUID> {

    boolean existsByBlockerIdAndBlockedId(UUID blockerId, UUID blockedId);

    @Modifying
    @Query("DELETE FROM UserBlock b WHERE b.blocker.id = :blockerId AND b.blocked.id = :blockedId")
    void deleteByBlockerIdAndBlockedId(@Param("blockerId") UUID blockerId, @Param("blockedId") UUID blockedId);

    /** Keyset page of the users a user blocked, most recently blocked first. Null cursor values for the first page. */
    @Query("""
            SELECT b FROM UserBlock b JOIN FETCH b.blocked
            WHERE b.blocker.id = :blockerId
              AND (CAST(:afterTime AS OffsetDateTime) IS NULL OR b.createdAt < :afterTime
                   OR (b.createdAt = :afterTime AND b.id < :afterId))
            ORDER BY b.createdAt DESC, b.id DESC""")
    List<UserBlock> findPage(@Param("blockerId") UUID blockerId, @Param("afterTime") OffsetDateTime afterTime,
                             @Param("afterId") UUID afterId, Limit limit);

    long countByBlockerId(UUID blockerId);

    long countByBlockedId(UUID blockedId);

    /** Back office: users this user blocks, most recently blocked first. */
    @Query(value = "SELECT b FROM UserBlock b JOIN FETCH b.blocked WHERE b.blocker.id = :userId ORDER BY b.createdAt DESC, b.id DESC",
            countQuery = "SELECT COUNT(b) FROM UserBlock b WHERE b.blocker.id = :userId")
    Page<UserBlock> findBlocking(@Param("userId") UUID userId, Pageable pageable);

    /** Back office: users blocking this user, most recently blocked first. */
    @Query(value = "SELECT b FROM UserBlock b JOIN FETCH b.blocker WHERE b.blocked.id = :userId ORDER BY b.createdAt DESC, b.id DESC",
            countQuery = "SELECT COUNT(b) FROM UserBlock b WHERE b.blocked.id = :userId")
    Page<UserBlock> findBlockedBy(@Param("userId") UUID userId, Pageable pageable);
}
