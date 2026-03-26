package com.vomattapi.application.dto.vote;

import java.time.LocalDateTime;
import java.util.List;

import lombok.Data;

@Data
public class VoteResultResponse {
    
    private String id;
    private String title;
    private String description;
    private String creatorId;
    private String creatorUsername;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private boolean isActive;
    private boolean allowMultipleChoices;
    private boolean isAnonymous;
    private LocalDateTime createdAt;
    private long totalVotes;
    private long totalParticipants;
    private boolean isVotingActive;
    private List<VoteOptionResultResponse> options;
    
    @Data
    public static class VoteOptionResultResponse {
        private String id;
        private String text;
        private String description;
        private Integer displayOrder;
        private long voteCount;
        private double percentage;
        private List<VoterResponse> voters;
    }
    
    @Data
    public static class VoterResponse {
        private String userId;
        private String username;
        private LocalDateTime votedAt;
    }
}