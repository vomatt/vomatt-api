package com.vomatt.repository;

import java.time.OffsetDateTime;
import java.util.Collection;
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

    /**
     * Transaction-scoped advisory lock on one (follower, followed) pair; followUser and blockUser take it before
     * their check / insert / delete, so a follow and a block of the same pair run one after the other.
     */
    @Query(value = """
            SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(CAST(:followerId AS text) || CAST(:followedId AS text), 0))""",
            nativeQuery = true)
    int lockPair(@Param("followerId") UUID followerId, @Param("followedId") UUID followedId);

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

    /**
     * Follow {@code f} of {@code :userId} by a mutual follower: an active user the viewer follows, with no block between
     * them and the viewer in either direction.
     */
    String MUTUAL_FOLLOWER = """
            f.followed.id = :userId AND f.follower.active = true
              AND EXISTS (SELECT 1 FROM UserFollow v WHERE v.follower.id = :viewerId AND v.followed.id = f.follower.id)
              AND NOT EXISTS (SELECT 1 FROM UserBlock b
                              WHERE (b.blocker.id = :viewerId AND b.blocked.id = f.follower.id)
                                 OR (b.blocker.id = f.follower.id AND b.blocked.id = :viewerId))""";

    /** Keyset page of the user's followers whom the viewer follows, in follower list order (most recently followed first). */
    @Query("SELECT f FROM UserFollow f JOIN FETCH f.follower WHERE " + MUTUAL_FOLLOWER + """
              AND (CAST(:afterTime AS OffsetDateTime) IS NULL OR f.createdAt < :afterTime
                   OR (f.createdAt = :afterTime AND f.id < :afterId))
            ORDER BY f.createdAt DESC, f.id DESC""")
    List<UserFollow> findMutualFollowersPage(@Param("userId") UUID userId, @Param("viewerId") UUID viewerId,
                                             @Param("afterTime") OffsetDateTime afterTime, @Param("afterId") UUID afterId,
                                             Limit limit);

    /** Size of the whole {@link #findMutualFollowersPage} list. */
    @Query("SELECT COUNT(f) FROM UserFollow f WHERE " + MUTUAL_FOLLOWER)
    long countMutualFollowers(@Param("userId") UUID userId, @Param("viewerId") UUID viewerId);

    /** Follows between the viewer and any of the users, in either direction; one query for a page of users. */
    @Query("""
            SELECT new com.vomatt.repository.FollowEdge(f.follower.id, f.followed.id) FROM UserFollow f
            WHERE (f.follower.id = :viewerId AND f.followed.id IN :userIds)
               OR (f.followed.id = :viewerId AND f.follower.id IN :userIds)""")
    List<FollowEdge> findEdgesWithViewer(@Param("viewerId") UUID viewerId, @Param("userIds") Collection<UUID> userIds);

    /** Data export: every user the user follows, suspended ones included, oldest first. */
    @Query("SELECT f FROM UserFollow f JOIN FETCH f.followed WHERE f.follower.id = :followerId ORDER BY f.createdAt, f.id")
    List<UserFollow> findAllByFollower(@Param("followerId") UUID followerId);
}
