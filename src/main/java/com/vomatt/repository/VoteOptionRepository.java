package com.vomatt.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.vomatt.entity.VoteOption;

@Repository
public interface VoteOptionRepository extends JpaRepository<VoteOption, UUID> {

    @Query("SELECT vo FROM VoteOption vo WHERE vo.vote.id = :voteId ORDER BY vo.displayOrder ASC, vo.createdAt ASC")
    List<VoteOption> findByVoteIdOrderByDisplayOrder(@Param("voteId") UUID voteId);

    /** Atomic +/- on the stored option count; never read-modify-write. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE vomatt.vote_options SET vote_count = vote_count + :delta WHERE id = :optionId",
           nativeQuery = true)
    void adjustVoteCount(@Param("optionId") UUID optionId, @Param("delta") int delta);

    /**
     * Releases a user's Ballots from option counts; run before deleting the user (user_votes cascade). Only Polls that
     * have not Ended: an Ended Poll's result is frozen, and its sealed Anonymous Ballots (option_id NULL) could not be
     * released from any option anyway. VoteRepository.decrementTurnoutForUserBallots applies the same rule.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE vomatt.vote_options o SET vote_count = o.vote_count - 1
            FROM vomatt.user_votes uv JOIN vomatt.votes v ON v.id = uv.vote_id AND v.end_time > now()
            WHERE uv.option_id = o.id AND uv.user_id = :userId""", nativeQuery = true)
    void decrementForUserBallots(@Param("userId") UUID userId);
}
