package com.vomatt.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Limit;
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

    List<User> findByEmailIn(Collection<String> emails);

    Optional<User> findByPhoneNumber(String phoneNumber);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    boolean existsByPhoneNumber(String phoneNumber);

    boolean existsByIdAndActiveFalse(UUID id);

    /**
     * Keyset page of username search (case-insensitive contains), ordered by username; null cursor for the first page.
     * The bigram GIN index finds candidates and LIKE confirms the contiguous match, as in Poll search (ADR 0001).
     */
    default List<User> searchByUsernameAfter(String username, String afterUsername, Limit limit) {
        return searchByUsernameAfter(username, "%" + VoteListRepository.escapeLike(username.toLowerCase()) + "%",
                afterUsername, limit);
    }

    // ponytail: a 1-character term has no bigrams, so @> '{}' matches every row and LIKE alone filters (full scan);
    // require 2+ characters if that ever shows up in slow queries
    @Query(value = """
            SELECT u.* FROM vomatt.users u
            WHERE u.username_bigrams @> vomatt.text_bigrams(:username)
              AND lower(u.username) LIKE :pattern ESCAPE '\\' AND u.active = true
              AND (CAST(:afterUsername AS text) IS NULL OR u.username > CAST(:afterUsername AS text))
            ORDER BY u.username""", nativeQuery = true)
    List<User> searchByUsernameAfter(@Param("username") String username, @Param("pattern") String pattern,
                                     @Param("afterUsername") String afterUsername, Limit limit);

    /**
     * Find users by exact username match
     */
    @Query("SELECT u FROM User u WHERE LOWER(u.username) = LOWER(:username) AND u.active = true")
    List<User> findByUsernameIgnoreCase(@Param("username") String username);

    /**
     * Fetch the User profile and stats in one query (avoids N+1). Each stat is a scalar subquery, so the two counts
     * never multiply into a polls x ballots join.
     * totalPolls counts only Polls that have opened (Open, Ended), consistent with the Poll list on the public page:
     * excludes Scheduled, Deleted, non-Public, and those cancelled before opening.
     * totalVotes counts Ballots only on Public, non-Deleted Polls, so a public profile does not reveal participation
     * in Unlisted or Invite-only Polls.
     */
    @Query("SELECT u.id AS id, u.username AS username, u.displayName AS displayName, u.bio AS bio, u.avatarUrl AS avatarUrl, " +
           "u.createdAt AS createdAt, u.email AS email, u.firstName AS firstName, " +
           "u.lastName AS lastName, u.location AS location, u.points AS points, " +
           "u.membershipLevel AS membershipLevel, u.active AS active, " +
           "(SELECT COUNT(v) FROM Vote v WHERE v.creator.id = u.id AND v.startTime <= CURRENT_TIMESTAMP " +
           "AND v.endTime > v.startTime AND v.deletedAt IS NULL AND v.audience = com.vomatt.entity.Audience.PUBLIC) AS totalPolls, " +
           "(SELECT COUNT(uv) FROM UserVote uv WHERE uv.user.id = u.id AND uv.vote.deletedAt IS NULL " +
           "AND uv.vote.audience = com.vomatt.entity.Audience.PUBLIC) AS totalVotes " +
           "FROM User u " +
           "WHERE u.username = :username")
    Optional<UserProfileProjection> findProfileByUsername(@Param("username") String username);
}
