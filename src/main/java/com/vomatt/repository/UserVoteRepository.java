package com.vomatt.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.vomatt.entity.UserVote;

@Repository
public interface UserVoteRepository extends JpaRepository<UserVote, UUID> {

    List<UserVote> findByUserIdAndVoteId(UUID userId, UUID voteId);

    List<UserVote> findByUserId(UUID userId);

    List<UserVote> findByVoteId(UUID voteId);

    boolean existsByUserIdAndVoteId(UUID userId, UUID voteId);

    boolean existsByUserIdAndVoteIdAndOptionId(UUID userId, UUID voteId, UUID optionId);

    Optional<UserVote> findByUserIdAndVoteIdAndOptionId(UUID userId, UUID voteId, UUID optionId);

    @Query("SELECT COUNT(mv) FROM UserVote mv WHERE mv.vote.id = :voteId")
    long countByVoteId(@Param("voteId") UUID voteId);

    @Query("SELECT COUNT(mv) FROM UserVote mv WHERE mv.option.id = :optionId")
    long countByOptionId(@Param("optionId") UUID optionId);

    void deleteByUserIdAndVoteId(UUID userId, UUID voteId);

    /** Keyset page of a Poll's Ballots, oldest first; pass null cursor values for the first page. */
    @Query("""
            SELECT uv FROM UserVote uv JOIN FETCH uv.user JOIN FETCH uv.option
            WHERE uv.vote.id = :voteId
              AND (CAST(:afterTime AS OffsetDateTime) IS NULL OR uv.createdAt > :afterTime
                   OR (uv.createdAt = :afterTime AND uv.id > :afterId))
            ORDER BY uv.createdAt, uv.id""")
    List<UserVote> findVoterPage(@Param("voteId") UUID voteId, @Param("afterTime") OffsetDateTime afterTime,
                                 @Param("afterId") UUID afterId, Limit limit);

    /** Serialises Ballot changes of one user in one Poll until the transaction ends. */
    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(hashtext(:userId), hashtext(:voteId))", nativeQuery = true)
    Integer lockBallot(@Param("userId") String userId, @Param("voteId") String voteId);

    void deleteByUserIdAndVoteIdAndOptionId(UUID userId, UUID voteId, UUID optionId);

    @Query("SELECT COUNT(DISTINCT uv.vote.id) FROM UserVote uv WHERE uv.user.id = :userId")
    long countDistinctVoteByUserId(@Param("userId") UUID userId);

    @Query("SELECT COUNT(DISTINCT uv.user.id) FROM UserVote uv WHERE uv.vote.id = :voteId")
    long countDistinctUserByVoteId(@Param("voteId") UUID voteId);

    @Query("SELECT uv.option.id AS optionId, COUNT(uv) AS count FROM UserVote uv WHERE uv.vote.id = :voteId GROUP BY uv.option.id")
    List<OptionVoteCount> countByOptionGroupedForVote(@Param("voteId") UUID voteId);
}
