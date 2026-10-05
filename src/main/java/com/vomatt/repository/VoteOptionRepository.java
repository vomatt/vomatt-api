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

    List<VoteOption> findByVoteIdOrderByDisplayOrderAsc(UUID voteId);

    @Query("SELECT vo FROM VoteOption vo WHERE vo.vote.id = :voteId ORDER BY vo.displayOrder ASC, vo.createdAt ASC")
    List<VoteOption> findByVoteIdOrderByDisplayOrder(@Param("voteId") UUID voteId);

    @Query("SELECT COUNT(mv) FROM UserVote mv WHERE mv.option.id = :optionId")
    long countVotesByOptionId(@Param("optionId") UUID optionId);

    /** Atomic +/- on the stored option count; never read-modify-write. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE vomatt.vote_options SET vote_count = vote_count + :delta WHERE id = :optionId",
           nativeQuery = true)
    void adjustVoteCount(@Param("optionId") UUID optionId, @Param("delta") int delta);

    /** Releases a user's Ballots from option counts; run before deleting the user (user_votes cascade). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE vomatt.vote_options o SET vote_count = o.vote_count - 1
            FROM vomatt.user_votes uv
            WHERE uv.option_id = o.id AND uv.user_id = :userId""", nativeQuery = true)
    void decrementForUserBallots(@Param("userId") UUID userId);
}
