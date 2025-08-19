package com.vomattapi.application.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.vomattapi.application.dto.request.CreateVoteRequest;
import com.vomattapi.application.dto.request.VoteRequest;
import com.vomattapi.application.dto.response.VoteResponse;
import com.vomattapi.application.dto.response.VoteResultResponse;
import com.vomattapi.domain.vote.Vote;

public interface VoteService {
    
    VoteResponse createVote(CreateVoteRequest request, String creatorId);
    
    VoteResponse getVote(String voteId);
    
    List<VoteResponse> getActiveVotes();
    
    Page<VoteResponse> getActiveVotes(Pageable pageable);
    
    List<VoteResponse> getVotesByCreator(String creatorId);
    
    VoteResponse vote(String voteId, VoteRequest request, String memberId, String ipAddress);
    
    VoteResponse removeVote(String voteId, String optionId, String memberId);
    
    VoteResultResponse getVoteResults(String voteId);
    
    boolean hasUserVoted(String voteId, String memberId);
    
    List<String> getUserVoteOptions(String voteId, String memberId);
    
    VoteResponse deactivateVote(String voteId, String creatorId);
    
    void processExpiredVotes();
}