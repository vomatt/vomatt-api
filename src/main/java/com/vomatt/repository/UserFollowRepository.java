package com.vomatt.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.vomatt.entity.UserFollow;

@Repository
public interface UserFollowRepository extends JpaRepository<UserFollow, UUID> {

    /** Idempotent, also under concurrent requests; returns 1 when the follow is new, 0 when it already existed. */
    @Modifying
    @Query(value = """
            INSERT INTO vomatt.user_follows (id, follower_id, followed_id) VALUES (:id, :followerId, :followedId)
            ON CONFLICT (follower_id, followed_id) DO NOTHING""", nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("followerId") UUID followerId, @Param("followedId") UUID followedId);

    /** @return 1 when a follow was removed, 0 when there was none */
    @Modifying
    @Query("DELETE FROM UserFollow f WHERE f.follower.id = :followerId AND f.followed.id = :followedId")
    int deleteByFollowerIdAndFollowedId(@Param("followerId") UUID followerId, @Param("followedId") UUID followedId);

    /**
     * Keyset page of the users a user follows, most recently followed first; suspended users are left out, as in user
     * search. Null cursor values for the first page.
     */
    @Query("""
            SELECT f FROM UserFollow f JOIN FETCH f.followed
            WHERE f.follower.id = :followerId AND f.followed.active = true
              AND (CAST(:afterTime AS OffsetDateTime) IS NULL OR f.createdAt < :afterTime
                   OR (f.createdAt = :afterTime AND f.id < :afterId))
            ORDER BY f.createdAt DESC, f.id DESC""")
    List<UserFollow> findPage(@Param("followerId") UUID followerId, @Param("afterTime") OffsetDateTime afterTime,
                              @Param("afterId") UUID afterId, Limit limit);

    /** Keyset page of the users who follow a user, most recently followed first; suspended followers are left out. */
    @Query("""
            SELECT f FROM UserFollow f JOIN FETCH f.follower
            WHERE f.followed.id = :followedId AND f.follower.active = true
              AND (CAST(:afterTime AS OffsetDateTime) IS NULL OR f.createdAt < :afterTime
                   OR (f.createdAt = :afterTime AND f.id < :afterId))
            ORDER BY f.createdAt DESC, f.id DESC""")
    List<UserFollow> findFollowersPage(@Param("followedId") UUID followedId, @Param("afterTime") OffsetDateTime afterTime,
                                       @Param("afterId") UUID afterId, Limit limit);

    /** Data export: every user the user follows, suspended ones included, oldest first. */
    @Query("SELECT f FROM UserFollow f JOIN FETCH f.followed WHERE f.follower.id = :followerId ORDER BY f.createdAt, f.id")
    List<UserFollow> findAllByFollower(@Param("followerId") UUID followerId);
}
