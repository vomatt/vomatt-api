package com.vomatt.repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.vomatt.common.util.LikePattern;
import com.vomatt.entity.Plan;
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

    /**
     * Key-share locks the accounts, the lock a foreign-key insert referencing them takes, in id order. An account
     * deletion in flight is waited for here, so checks made afterwards see it instead of the insert failing its key.
     */
    @Query(value = "SELECT id FROM vomatt.users WHERE id IN (:ids) ORDER BY id FOR KEY SHARE", nativeQuery = true)
    List<UUID> lockKeyShare(@Param("ids") Collection<UUID> ids);

    /**
     * Locks the person's row for a plan or community-ownership change and returns their plan as stored now, so a
     * check and the write it guards cannot interleave with another (two creations, a plan change).
     * FOR NO KEY UPDATE, as {@link #lockActiveAdminIds}. Every such change takes this lock before any community row.
     * Run it through {@link LockTimeout#within}.
     */
    @Query(value = "SELECT plan FROM vomatt.users WHERE id = :id FOR NO KEY UPDATE", nativeQuery = true)
    Optional<String> lockPlan(@Param("id") UUID id);

    @Modifying
    @Query("UPDATE User u SET u.plan = :plan WHERE u.id = :id")
    void setPlan(@Param("id") UUID id, @Param("plan") Plan plan);

    /**
     * The selection deadline person {@code u} should have: while they own more active, unlocked community accounts than
     * their plan's limit, a running one keeps its date and a new one is {@code :deadline}; within the limit, none.
     */
    String SELECTION_DEADLINE = """
            CASE WHEN (SELECT count(*) FROM vomatt.community_members m JOIN vomatt.users c ON c.id = m.community_user_id
                       WHERE m.user_id = u.id AND m.role = 'OWNER' AND c.active AND c.locked_at IS NULL)
                      > CASE u.plan WHEN 'PRO' THEN :proLimit ELSE :freeLimit END
                 THEN COALESCE(u.community_selection_deadline, :deadline) END""";

    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE vomatt.users u SET community_selection_deadline = " + SELECTION_DEADLINE + " WHERE u.id IN (:ids)",
           nativeQuery = true)
    void reconcileSelectionDeadlines(@Param("ids") Collection<UUID> ids, @Param("freeLimit") int freeLimit,
                                     @Param("proLimit") int proLimit, @Param("deadline") OffsetDateTime deadline);

    /**
     * {@link #reconcileSelectionDeadlines} of everyone whose deadline is out of step, returning how many it changed. Rows
     * another transaction holds are skipped: a plan or ownership change reconciles its own people, and the next run gets the rest.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE vomatt.users u SET community_selection_deadline = " + SELECTION_DEADLINE + " WHERE u.id IN ("
            + "SELECT u.id FROM vomatt.users u WHERE u.id IN (SELECT id FROM vomatt.users WHERE community_selection_deadline IS NOT NULL "
            + "UNION SELECT user_id FROM vomatt.community_members WHERE role = 'OWNER') "
            + "AND (" + SELECTION_DEADLINE + ") IS DISTINCT FROM u.community_selection_deadline "
            + "FOR NO KEY UPDATE SKIP LOCKED)", nativeQuery = true)
    int reconcileAllSelectionDeadlines(@Param("freeLimit") int freeLimit, @Param("proLimit") int proLimit,
                                       @Param("deadline") OffsetDateTime deadline);

    @Query("SELECT u.communitySelectionDeadline FROM User u WHERE u.id = :id")
    Optional<OffsetDateTime> findSelectionDeadline(@Param("id") UUID id);

    /** Users whose community selection deadline has passed: the selection job's work list. */
    @Query("SELECT u.id FROM User u WHERE u.communitySelectionDeadline <= :now")
    List<UUID> findSelectionDueBy(@Param("now") OffsetDateTime now);

    boolean existsByIdAndLockedAtIsNotNull(UUID id);

    /**
     * Locks the community account rows in id order, so two transactions changing overlapping sets never deadlock. Run
     * it after {@link #lockPlan} and through {@link LockTimeout#within}.
     */
    @Query(value = "SELECT id FROM vomatt.users WHERE id IN (:ids) ORDER BY id FOR NO KEY UPDATE", nativeQuery = true)
    List<UUID> lockIds(@Param("ids") Collection<UUID> ids);

    /**
     * {@link #lockIds} for only the accounts not yet in the wanted state (locked when {@code lock}), returning them.
     * A row another transaction changed while this one waited is re-read, so the result is exactly what to change.
     */
    @Query(value = "SELECT id FROM vomatt.users WHERE id IN (:ids) AND (locked_at IS NULL) = :lock ORDER BY id FOR NO KEY UPDATE",
           nativeQuery = true)
    List<UUID> lockToChange(@Param("ids") Collection<UUID> ids, @Param("lock") boolean lock);

    /**
     * Records a sign-in in one UPDATE of those two columns: a merge of the signed-in User would SELECT first and could
     * write a stale {@code active} back over a suspension committed in between. Its own transaction, since sign-in
     * runs outside one; a bulk UPDATE skips {@code @PreUpdate}, so {@code updated_at} is set here.
     */
    @Transactional
    @Modifying
    @Query("UPDATE User u SET u.lastLoginAt = :now, u.updatedAt = :now WHERE u.id = :id")
    void recordLogin(@Param("id") UUID id, @Param("now") OffsetDateTime now);

    /**
     * Locks the community accounts for {@code reason}. The COMMUNITY_LOCKED notification is keyed by the account, so read
     * state left from an earlier lock is dropped and this lock is unread.
     */
    @Modifying
    @Query(value = """
            WITH unread AS (DELETE FROM vomatt.notification_reads WHERE kind = 'COMMUNITY_LOCKED' AND source_id IN (:ids))
            UPDATE vomatt.users SET locked_at = :now, lock_reason = :reason WHERE id IN (:ids)""", nativeQuery = true)
    void markLocked(@Param("ids") Collection<UUID> ids, @Param("now") OffsetDateTime now, @Param("reason") String reason);

    @Modifying
    @Query(value = "UPDATE vomatt.users SET locked_at = NULL, lock_reason = NULL WHERE id IN (:ids)", nativeQuery = true)
    void markUnlocked(@Param("ids") Collection<UUID> ids);

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
           "u.lastName AS lastName, u.location AS location, u.gender AS gender, u.active AS active, u.roles AS roles, " +
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

    /** Own-profile count matching My Polls: every non-Deleted Poll the user created, any Audience and status. */
    @Query("SELECT COUNT(v) FROM Vote v WHERE v.creator.id = :userId AND v.deletedAt IS NULL")
    long countCreatedPolls(@Param("userId") UUID userId);

    /**
     * Own-profile count matching My Polls: non-Deleted Polls the user holds a Ballot in (own Polls and Anonymous Ballots
     * included, any Audience), minus those whose owner blocked the user, as {@code VoteListRepository.findMine} does.
     */
    @Query("SELECT COUNT(DISTINCT uv.vote.id) FROM UserVote uv WHERE uv.user.id = :userId AND uv.vote.deletedAt IS NULL " +
           "AND NOT EXISTS (SELECT 1 FROM UserBlock b WHERE b.blocker.id = uv.vote.creator.id AND b.blocked.id = :userId)")
    long countVotedPolls(@Param("userId") UUID userId);
}
