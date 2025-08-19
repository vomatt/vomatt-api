package com.vomattapi.domain.vote.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.vomattapi.domain.vote.VoteOption;

@Repository
public interface VoteOptionRepository extends JpaRepository<VoteOption, String> {
    
    List<VoteOption> findByVoteIdOrderByDisplayOrderAsc(String voteId);
    
    @Query("SELECT vo FROM VoteOption vo WHERE vo.vote.id = :voteId ORDER BY vo.displayOrder ASC, vo.createdAt ASC")
    List<VoteOption> findByVoteIdOrderByDisplayOrder(@Param("voteId") String voteId);
    
    @Query("SELECT COUNT(mv) FROM MemberVote mv WHERE mv.option.id = :optionId")
    long countVotesByOptionId(@Param("optionId") String optionId);
}