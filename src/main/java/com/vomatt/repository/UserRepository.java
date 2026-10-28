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

import com.vomatt.common.util.LikePattern;
import com.vomatt.entity.User;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {
    /** SQL behind {@link #searchByUsernameRaw}; shared so tests can EXPLAIN the real query. */
    String USERNAME_SEARCH = """
            SELECT u.* FROM vomatt.users u
            WHERE u.username_bigrams @> vomatt.text_bigrams(:username)
              AND lower(u.username) LIKE :pattern ESCAPE '\\' AND u.active = true
              AND (CAST(:afterUsername AS text) IS NULL OR u.username > CAST(:afterUsername AS text))
            ORDER BY u.username""";

    Optional<User> findByUsername(String username);

    /** A suspended user is not found, as on their profile: callers answer both with the same 404. */
    default Optional<User> findActiveByUsername(String username) {
        return findByUsername(username).filter(User::isActive);
    }

    Optional<User> findByEmail(String email);

    List<User> findByEmailIn(Collection<String> emails);

    Optional<User> findByPhoneNumber(String phoneNumber);

    boolean existsByUsername(String username);

    @Query("SELECT u.id FROM User u WHERE u.username IN :usernames")
    List<UUID> findIdsByUsernameIn(@Param("usernames") Collection<String> usernames);

    /** Of the given users, those whose current username is one of {@code usernames}. */
    @Query("SELECT u.id FROM User u WHERE u.id IN :ids AND u.username IN :usernames")
    List<UUID> findIdsByIdInAndUsernameIn(@Param("ids") Collection<UUID> ids, @Param("usernames") Collection<String> usernames);

    boolean existsByEmail(String email);

    boolean existsByPhoneNumber(String phoneNumber);

    /** The account's {@code active} flag: empty when the account no longer exists, false when it is suspended. */
    @Query("SELECT u.active FROM User u WHERE u.id = :id")
    Optional<Boolean> findActiveById(@Param("id") UUID id);

    /**
     * Locks every active admin row and returns their ids, so concurrent admin removals serialize: a waiter re-reads the
     * rows once the first commits and no longer sees an admin it suspended or demoted. FOR NO KEY UPDATE, not FOR UPDATE:
     * it does not conflict with the KEY SHARE lock that foreign-key inserts (Ballots, Comments, Polls) take on the user
     * row, so the admins' own writes neither block nor deadlock against it. Rows are locked in id order, so two guards
     * never deadlock on each other. Run it through {@link LockTimeout#within}.
     */
    @Query(value = "SELECT id FROM vomatt.users WHERE active = true AND 'admin' = ANY(roles) ORDER BY id FOR NO KEY UPDATE", nativeQuery = true)
    List<UUID> lockActiveAdminIds();

    /**
     * Locks the account row before its deletion touches any other row, so deletion locks users before Polls and
     * Comments, the order every users-guarded insert (reports, bookmarks, likes, tag follows) takes. A cycle remains
     * only with the same user acting on a row that deletion also locks while their own account is being deleted
     * (changing a Ballot, or writing to their own Poll by foreign key); Postgres aborts one side of it.
     */
    @Query(value = "SELECT id FROM vomatt.users WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<UUID> lockForDelete(@Param("id") UUID id);

    /** Whether the account is active and still holds the admin role; checked per request on /api/admin/**. */
    @Query(value = "SELECT EXISTS (SELECT 1 FROM vomatt.users WHERE id = :id AND active = true AND 'admin' = ANY(roles))",
           nativeQuery = true)
    boolean isActiveAdmin(@Param("id") UUID id);

    /**
     * Keyset page of username search (case-insensitive contains), ordered by username; null cursor for the first page.
     * The bigram GIN index finds candidates and LIKE confirms the contiguous match, as in Poll search (ADR 0001).
     */
    default List<User> searchByUsernameAfter(String username, String afterUsername, Limit limit) {
        return searchByUsernameRaw(username, LikePattern.containsPattern(username), afterUsername, limit);
    }

    // ponytail: a 1-character term has no bigrams, so @> '{}' matches every row and LIKE alone filters (full scan);
    // require 2+ characters if that ever shows up in slow queries
    @Query(value = USERNAME_SEARCH, nativeQuery = true)
    List<User> searchByUsernameRaw(@Param("username") String username, @Param("pattern") String pattern,
                                   @Param("afterUsername") String afterUsername, Limit limit);

    /**
     * Fetch the User profile and stats in one query (avoids N+1). Each stat is a scalar subquery, so the two counts
     * never multiply into a polls x ballots join.
     * totalPolls counts only Polls that have opened (Open, Ended), consistent with the Poll list on the public page:
     * excludes Scheduled, Deleted, non-Public, and those cancelled before opening.
     * totalVotes counts Ballots only on Public, non-Deleted Polls, so a public profile does not reveal participation
     * in Unlisted or Invite-only Polls.
     */
    @Query("SELECT u.id AS id, u.username AS username, u.displayName AS displayName, u.bio AS bio, u.avatarUrl AS avatarUrl, " +
           "u.createdAt AS createdAt, u.firstName AS firstName, " +
           "u.lastName AS lastName, u.location AS location, u.points AS points, " +
           "u.membershipLevel AS membershipLevel, u.active AS active, " +
           "(SELECT COUNT(v) FROM Vote v WHERE v.creator.id = u.id AND v.startTime <= CURRENT_TIMESTAMP " +
           "AND v.endTime > v.startTime AND v.deletedAt IS NULL AND v.audience = com.vomatt.entity.Audience.PUBLIC) AS totalPolls, " +
           "(SELECT COUNT(uv) FROM UserVote uv WHERE uv.user.id = u.id AND uv.vote.deletedAt IS NULL " +
           "AND uv.vote.audience = com.vomatt.entity.Audience.PUBLIC) AS totalVotes, " +
           // Same rows as the follower and following lists: suspended accounts left out, blocks not filtered
           "(SELECT COUNT(f) FROM UserFollow f WHERE f.followed.id = u.id AND f.follower.active = true) AS followersCount, " +
           "(SELECT COUNT(f) FROM UserFollow f WHERE f.follower.id = u.id AND f.followed.active = true) AS followingCount, " +
           // false for a null viewer and for the viewer's own row (follows and blocks of oneself cannot exist)
           "CASE WHEN EXISTS (SELECT 1 FROM UserFollow f WHERE f.follower.id = :viewerId AND f.followed.id = u.id) " +
           "THEN true ELSE false END AS following, " +
           "CASE WHEN EXISTS (SELECT 1 FROM UserBlock b WHERE b.blocker.id = :viewerId AND b.blocked.id = u.id) " +
           "THEN true ELSE false END AS blocked, " +
           "CASE WHEN EXISTS (SELECT 1 FROM UserFollow f WHERE f.follower.id = u.id AND f.followed.id = :viewerId) " +
           "THEN true ELSE false END AS followsViewer " +
           "FROM User u " +
           "WHERE u.username = :username")
    Optional<UserProfileProjection> findProfileForViewer(@Param("username") String username, @Param("viewerId") UUID viewerId);

    default Optional<UserProfileProjection> findProfileByUsername(String username) {
        return findProfileForViewer(username, null);
    }
}
