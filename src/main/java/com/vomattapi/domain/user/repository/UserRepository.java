package com.vomattapi.domain.user.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.vomattapi.domain.user.User;
import com.vomattapi.domain.vote.UserVote;
import com.vomattapi.domain.vote.Vote;

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

    /**
     * Find users by exact username match
     */
    @Query("SELECT u FROM User u WHERE LOWER(u.username) = LOWER(:username) AND u.active = true")
    List<User> findByUsernameIgnoreCase(@Param("username") String username);

    /**
     * 一次查詢取得 User profile 與統計數字（避免 N+1）
     */
    @Query("SELECT u.id AS id, u.username AS username, u.displayName AS displayName, u.bio AS bio, " +
           "u.createdAt AS createdAt, u.email AS email, u.firstName AS firstName, " +
           "u.lastName AS lastName, u.location AS location, u.points AS points, " +
           "u.membershipLevel AS membershipLevel, " +
           "COUNT(DISTINCT v.id) AS totalPolls, COUNT(DISTINCT uv.id) AS totalVotes " +
           "FROM User u " +
           "LEFT JOIN Vote v ON v.creator.id = u.id " +
           "LEFT JOIN UserVote uv ON uv.user.id = u.id " +
           "WHERE u.username = :username " +
           "GROUP BY u.id, u.username, u.displayName, u.bio, u.createdAt, " +
           "u.email, u.firstName, u.lastName, u.location, u.points, u.membershipLevel")
    Optional<UserProfileProjection> findProfileByUsername(@Param("username") String username);
}
