package com.vomattapi.domain.vote.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.vomattapi.domain.vote.MemberVote;

@Repository
public interface MemberVoteRepository extends JpaRepository<MemberVote, Long> {
    
    List<MemberVote> findByMemberIdAndVoteId(String memberId, String voteId);
    
    List<MemberVote> findByMemberId(String memberId);
    
    List<MemberVote> findByVoteId(String voteId);
    
    boolean existsByMemberIdAndVoteId(String memberId, String voteId);
    
    boolean existsByMemberIdAndVoteIdAndOptionId(String memberId, String voteId, String optionId);
    
    Optional<MemberVote> findByMemberIdAndVoteIdAndOptionId(String memberId, String voteId, String optionId);
    
    @Query("SELECT COUNT(mv) FROM MemberVote mv WHERE mv.vote.id = :voteId")
    long countByVoteId(@Param("voteId") String voteId);
    
    @Query("SELECT COUNT(mv) FROM MemberVote mv WHERE mv.option.id = :optionId")
    long countByOptionId(@Param("optionId") String optionId);
    
    void deleteByMemberIdAndVoteId(String memberId, String voteId);
    
    void deleteByMemberIdAndVoteIdAndOptionId(String memberId, String voteId, String optionId);
}