package com.vomatt.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.vomatt.entity.Vote;

import jakarta.persistence.LockModeType;

/** List queries live in {@link VoteListRepository}. */
@Repository
public interface VoteRepository extends JpaRepository<Vote, UUID> {

    long countByCreatorId(UUID creatorId);

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

    /** Atomic +1 on the Poll's View bucket for the current UTC hour. */
    // ponytail: Views of one Poll serialize on its current-hour row; buffer in Redis INCR + scheduled flush if a hot Poll needs more
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
