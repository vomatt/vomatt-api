package com.vomatt.votes.dto;

import java.time.OffsetDateTime;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class VoteResultResponse {
    
    private String id;
    private String title;
    private String description;
    private String creatorId;
    private String creatorUsername;
    private OffsetDateTime startTime;
    private OffsetDateTime endTime;
    private boolean isActive;
    private boolean allowMultipleChoices;
    private boolean isAnonymous;
    private OffsetDateTime createdAt;
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
        @Schema(description = "Support：選此選項的 Participant 比例（0–100）")
        private double percentage;
    }

}