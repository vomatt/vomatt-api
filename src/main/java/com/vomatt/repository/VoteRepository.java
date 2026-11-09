package com.vomatt.repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.vomatt.entity.Vote;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

/** List queries live in {@link VoteListRepository}. */
@Repository
public interface VoteRepository extends JpaRepository<Vote, UUID> {

    long countByCreatorId(UUID creatorId);

    boolean existsByIdAndDeletedAtIsNull(UUID id);

    /** Every Poll the user created, Deleted ones included (data export). */
    List<Vote> findByCreatorIdOrderByCreatedAtDesc(UUID creatorId);

    @Query("SELECT v FROM Vote v LEFT JOIN FETCH v.options WHERE v.id = :id")
    Optional<Vote> findByIdWithOptions(@Param("id") UUID id);

    /**
     * Exclusive Poll-row lock (Hibernate emits FOR NO KEY UPDATE on Postgres) for Ballot changes, Close, Edit and
     * Delete; each waits for the others on the same Poll. Ballots take it too, not a shared lock, because each one
     * updates the Poll's Turnout and two shared-lock holders updating the same row would deadlock.
     * Must be the first load of the Poll in the transaction, so its status is read from the locked row.
     * Call {@link LockTimeout#apply()} first so a waiter gives up instead of holding a pooled connection while the Poll is busy.
     */
    // ponytail: Ballots on one Poll serialize; move Turnout to a separate counter row if a hot Poll needs more throughput
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT v FROM Vote v WHERE v.id = :id")
    Optional<Vote> findByIdForUpdate(@Param("id") UUID id);

    /** Atomic +/- on the stored Turnout; never read-modify-write. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE vomatt.votes SET turnout = turnout + :delta WHERE id = :voteId", nativeQuery = true)
    void adjustTurnout(@Param("voteId") UUID voteId, @Param("delta") int delta);

    /**
     * Releases a user's Ballots from Turnout; run before deleting the user (user_votes cascade). Only Polls that have
     * not Ended, like VoteOptionRepository.decrementForUserBallots: an Ended Poll's result stays as it closed.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE vomatt.votes v SET turnout = v.turnout - 1
            FROM vomatt.user_votes uv
            WHERE uv.vote_id = v.id AND uv.user_id = :userId AND v.end_time > now()""", nativeQuery = true)
    void decrementTurnoutForUserBallots(@Param("userId") UUID userId);

    /** Idempotent, also under concurrent requests: a user's later shares of the same Poll are dropped. */
    @Modifying
    @Query(value = """
            INSERT INTO vomatt.vote_shares (vote_id, user_id, channel)
            VALUES (:voteId, :userId, :channel)
            ON CONFLICT (vote_id, user_id) DO NOTHING""", nativeQuery = true)
    int insertShareIfAbsent(@Param("voteId") UUID voteId, @Param("userId") UUID userId, @Param("channel") String channel);

    /** Atomic +1 on the stored share count; call only when {@link #insertShareIfAbsent} inserted a row. */
    @Modifying
    @Query(value = "UPDATE vomatt.votes SET share_count = share_count + 1 WHERE id = :voteId", nativeQuery = true)
    void incrementShareCount(@Param("voteId") UUID voteId);

    /** Releases a user's shares from the stored share counts; run before deleting the user (vote_shares cascade). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE vomatt.votes v SET share_count = v.share_count - 1
            FROM vomatt.vote_shares s
            WHERE s.vote_id = v.id AND s.user_id = :userId""", nativeQuery = true)
    void decrementShareCountForUserShares(@Param("userId") UUID userId);

    /**
     * Likes the Poll and adds one to its like count in one statement; idempotent, also under concurrent requests.
     * Returns 0 when the like already existed, the user does not exist or the Poll is gone or Deleted. The user and
     * Poll rows are key-share locked, so an account deletion still in flight is waited for and yields 0 rather than a
     * foreign-key failure; a key-share lock, unlike bookmarks' share lock, lets concurrent likers update the count.
     */
    @Modifying
    @Query(value = """
            WITH liked AS (
                INSERT INTO vomatt.vote_likes (id, user_id, vote_id)
                SELECT :id, :userId, :voteId
                WHERE EXISTS (SELECT 1 FROM vomatt.users WHERE id = :userId FOR KEY SHARE)
                  AND EXISTS (SELECT 1 FROM vomatt.votes WHERE id = :voteId AND deleted_at IS NULL FOR KEY SHARE)
                ON CONFLICT (user_id, vote_id) DO NOTHING
                RETURNING vote_id)
            UPDATE vomatt.votes SET like_count = like_count + 1 WHERE id IN (SELECT vote_id FROM liked)""", nativeQuery = true)
    int insertLikeIfAbsent(@Param("id") UUID id, @Param("userId") UUID userId, @Param("voteId") UUID voteId);

    /** Removes the like and takes one off the like count in one statement; 1 when a like was removed, 0 when there was none. */
    @Modifying
    @Query(value = """
            WITH unliked AS (
                DELETE FROM vomatt.vote_likes WHERE user_id = :userId AND vote_id = :voteId RETURNING vote_id)
            UPDATE vomatt.votes SET like_count = like_count - 1 WHERE id IN (SELECT vote_id FROM unliked)""", nativeQuery = true)
    int deleteLike(@Param("userId") UUID userId, @Param("voteId") UUID voteId);

    /** The like exists and its Poll is not Deleted. */
    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM vomatt.vote_likes l JOIN vomatt.votes v ON v.id = l.vote_id
                           WHERE l.user_id = :userId AND l.vote_id = :voteId AND v.deleted_at IS NULL)""", nativeQuery = true)
    boolean existsLiveLike(@Param("userId") UUID userId, @Param("voteId") UUID voteId);

    /** Of the given Polls, those the user liked; one query for a page of Polls. */
    // Names the table it reads: without it, the page's options and tags were no longer batch-fetched but loaded per Poll
    // (VoteListPostgresTest.shouldUseConstantQueriesPerPage)
    @QueryHints(@QueryHint(name = "org.hibernate.query.native.spaces", value = "vomatt.vote_likes"))
    @Query(value = "SELECT l.vote_id FROM vomatt.vote_likes l WHERE l.user_id = :userId AND l.vote_id IN (:voteIds)",
            nativeQuery = true)
    List<UUID> findLikedVoteIds(@Param("userId") UUID userId, @Param("voteIds") Collection<UUID> voteIds);

    /** Releases a user's likes from the stored like counts; run before deleting the user (vote_likes cascade). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE vomatt.votes v SET like_count = v.like_count - 1
            FROM vomatt.vote_likes l
            WHERE l.vote_id = v.id AND l.user_id = :userId""", nativeQuery = true)
    void decrementLikeCountForUserLikes(@Param("userId") UUID userId);

    /** Atomic +1 on the Poll's View bucket for the current UTC hour. */
    // ponytail: Views of one Poll serialize on its current-hour row and VoteViewRecorder caps writers at 2, so a hot Poll
    // drops Views once its queue waits past 2 s; buffer in Redis HINCRBY + scheduled flush if Trending needs them all
    @Modifying
    @Query(value = """
            INSERT INTO vomatt.vote_view_hours (vote_id, hour, views)
            VALUES (:voteId, date_trunc('hour', now(), 'UTC'), 1)
            ON CONFLICT (vote_id, hour) DO UPDATE SET views = vote_view_hours.views + 1""", nativeQuery = true)
    void incrementViewHour(@Param("voteId") UUID voteId);

    @Modifying
    @Query(value = "DELETE FROM vomatt.vote_view_hours WHERE hour < :before", nativeQuery = true)
    int deleteViewHoursBefore(@Param("before") OffsetDateTime before);
}
