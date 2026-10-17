package com.vomatt.repository;

import java.time.OffsetDateTime;
import java.util.Collection;
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

    Optional<UserVote> findByUserIdAndVoteId(UUID userId, UUID voteId);

    boolean existsByUserIdAndVoteId(UUID userId, UUID voteId);

    Optional<UserVote> findByUserIdAndVoteIdAndOptionId(UUID userId, UUID voteId, UUID optionId);

    /** The user's Selection in each of a page of Polls. */
    @Query("SELECT uv.vote.id AS voteId, uv.option.id AS optionId FROM UserVote uv "
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

    /** Anonymous Ballots in a Poll; the voter list's k-anonymity gate. */
    long countByVoteIdAndAnonymousTrue(UUID voteId);

}
