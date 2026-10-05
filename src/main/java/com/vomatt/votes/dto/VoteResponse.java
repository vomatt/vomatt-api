package com.vomatt.votes.dto;

import com.vomatt.tags.dto.TagDto;

import java.time.OffsetDateTime;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class VoteResponse {
    
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
    private OffsetDateTime updatedAt;
    private long totalVotes;
    private boolean isVotingActive;
    private List<VoteOptionResponse> options;
    private List<TagDto> tags;

    @Schema(description = "目前使用者選擇的選項 ID；未投票或未登入為 null", nullable = true)
    private String myOptionId;
    
    @Data
    public static class VoteOptionResponse {
        private String id;
        private String text;
        private String description;
        private Integer displayOrder;
        private OffsetDateTime createdAt;
        private long votes;
    }
}