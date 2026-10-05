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

    @Schema(description = "Turnout：投票人數，任何時候都可見")
    private long participantCount;
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
        @Schema(description = "此選項票數；Poll 結束前封存為 null", nullable = true)
        private Long votes;
    }
}