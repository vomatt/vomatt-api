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

import com.vomatt.entity.UserVote;

@Repository
public interface UserVoteRepository extends JpaRepository<UserVote, UUID> {

    Optional<UserVote> findByUserIdAndVoteId(UUID userId, UUID voteId);

    boolean existsByUserIdAndVoteId(UUID userId, UUID voteId);

    long countByUserId(UUID userId);

    Optional<UserVote> findByUserIdAndVoteIdAndOptionId(UUID userId, UUID voteId, UUID optionId);

    /** Every Ballot of the user with its Poll and option (null once sealed), oldest first (data export). */
    @Query("SELECT uv FROM UserVote uv JOIN FETCH uv.vote LEFT JOIN FETCH uv.option WHERE uv.user.id = :userId ORDER BY uv.createdAt")
    List<UserVote> findByUserIdWithVoteAndOption(@Param("userId") UUID userId);

    /** The user's Selection in each of a page of Polls. */
    @Query("SELECT uv.vote.id AS voteId, uv.option.id AS optionId, uv.anonymous AS anonymous FROM UserVote uv "
            + "WHERE uv.user.id = :userId AND uv.vote.id IN :voteIds")
    List<BallotSelection> findSelections(@Param("userId") UUID userId, @Param("voteIds") Collection<UUID> voteIds);

    /** Keyset page of a Poll's named Ballots (Anonymous Ballots left out), oldest first; pass null cursor values for the first page. */
    @Query("""
            SELECT uv FROM UserVote uv JOIN FETCH uv.user JOIN FETCH uv.option
            WHERE uv.vote.id = :voteId AND uv.anonymous = false
              AND (CAST(:afterTime AS OffsetDateTime) IS NULL OR uv.createdAt > :afterTime
                   OR (uv.createdAt = :afterTime AND uv.id > :afterId))
            ORDER BY uv.createdAt, uv.id""")
    List<UserVote> findVoterPage(@Param("voteId") UUID voteId, @Param("afterTime") OffsetDateTime afterTime,
                                 @Param("afterId") UUID afterId, Limit limit);

    /** Anonymous Ballots in a Poll other than the caller's; while there are any, the voter list names no options. */
    long countByVoteIdAndAnonymousTrueAndUserIdNot(UUID voteId, UUID userId);

    /**
     * Seals the Anonymous Ballots of Polls Ended by {@code now}: the option is dropped from the row, so the database
     * can no longer say what an anonymous Participant chose (per-option totals stay in vote_options.vote_count).
     * Irreversible by design; a Cancelled Poll (end before start) counts as Ended too.
     */
    @Modifying
    @Query(value = """
            UPDATE vomatt.user_votes uv SET option_id = NULL
            WHERE uv.anonymous AND uv.option_id IS NOT NULL
              AND EXISTS (SELECT 1 FROM vomatt.votes v WHERE v.id = uv.vote_id AND v.end_time <= :now)""",
            nativeQuery = true)
    int sealAnonymousBallotsOfPollsEndedBy(@Param("now") OffsetDateTime now);

}
