package com.vomattapi.domain.vote.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.vomattapi.domain.vote.UserVote;

@Repository
public interface UserVoteRepository extends JpaRepository<UserVote, Long> {
    
    List<UserVote> findByUserIdAndVoteId(String userId, String voteId);

    List<UserVote> findByUserId(String userId);

    List<UserVote> findByVoteId(String voteId);

    boolean existsByUserIdAndVoteId(String userId, String voteId);

    boolean existsByUserIdAndVoteIdAndOptionId(String userId, String voteId, String optionId);

    Optional<UserVote> findByUserIdAndVoteIdAndOptionId(String userId, String voteId, String optionId);
    
    @Query("SELECT COUNT(mv) FROM UserVote mv WHERE mv.vote.id = :voteId")
    long countByVoteId(@Param("voteId") String voteId);
    
    @Query("SELECT COUNT(mv) FROM UserVote mv WHERE mv.option.id = :optionId")
    long countByOptionId(@Param("optionId") String optionId);
    
    void deleteByUserIdAndVoteId(String userId, String voteId);

    void deleteByUserIdAndVoteIdAndOptionId(String userId, String voteId, String optionId);
}