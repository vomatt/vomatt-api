package com.vomatt.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
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
}
