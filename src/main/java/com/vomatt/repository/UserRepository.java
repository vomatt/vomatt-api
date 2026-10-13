package com.vomatt.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.vomatt.entity.User;
import com.vomatt.entity.UserVote;
import com.vomatt.entity.Vote;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    Optional<User> findByPhoneNumber(String phoneNumber);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    boolean existsByPhoneNumber(String phoneNumber);

    /**
     * Search users by username containing the search term (case-insensitive)
     */
    @Query("SELECT u FROM User u WHERE LOWER(u.username) LIKE LOWER(CONCAT('%', :username, '%')) AND u.active = true")
    Page<User> searchByUsername(@Param("username") String username, Pageable pageable);

    /** Keyset page of username search, ordered by username; null cursor for the first page. */
    @Query("""
            SELECT u FROM User u
            WHERE LOWER(u.username) LIKE LOWER(CONCAT('%', :username, '%')) AND u.active = true
              AND (CAST(:afterUsername AS String) IS NULL OR u.username > :afterUsername)
            ORDER BY u.username""")
    List<User> searchByUsernameAfter(@Param("username") String username,
                                     @Param("afterUsername") String afterUsername, Limit limit);

    /**
     * Find users by exact username match
     */
    @Query("SELECT u FROM User u WHERE LOWER(u.username) = LOWER(:username) AND u.active = true")
    List<User> findByUsernameIgnoreCase(@Param("username") String username);

    /**
     * Fetch the User profile and stats in one query (avoids N+1). totalPolls counts only Polls that have opened (Open, Ended),
     * consistent with the Poll list on the public page: excludes Scheduled, Deleted, non-Public, and those cancelled before opening.
     */
    @Query("SELECT u.id AS id, u.username AS username, u.displayName AS displayName, u.bio AS bio, " +
           "u.createdAt AS createdAt, u.email AS email, u.firstName AS firstName, " +
           "u.lastName AS lastName, u.location AS location, u.points AS points, " +
           "u.membershipLevel AS membershipLevel, u.active AS active, " +
           "COUNT(DISTINCT v.id) AS totalPolls, COUNT(DISTINCT uv.id) AS totalVotes " +
           "FROM User u " +
           "LEFT JOIN Vote v ON v.creator.id = u.id AND v.startTime <= CURRENT_TIMESTAMP AND v.endTime > v.startTime AND v.deletedAt IS NULL " +
           "AND v.audience = com.vomatt.entity.Audience.PUBLIC " +
           "LEFT JOIN UserVote uv ON uv.user.id = u.id AND uv.vote.deletedAt IS NULL " +
           "WHERE u.username = :username " +
           "GROUP BY u.id, u.username, u.displayName, u.bio, u.createdAt, " +
           "u.email, u.firstName, u.lastName, u.location, u.points, u.membershipLevel, u.active")
    Optional<UserProfileProjection> findProfileByUsername(@Param("username") String username);
}
