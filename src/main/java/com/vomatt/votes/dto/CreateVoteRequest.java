package com.vomatt.votes.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import com.vomatt.entity.VoterVisibility;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class CreateVoteRequest {
    
    @NotBlank(message = "Title is required")
    @Size(max = 200, message = "Title cannot exceed 200 characters")
    private String title;
    
    @Size(max = 1000, message = "Description cannot exceed 1000 characters")
    private String description;
    
    @NotEmpty(message = "At least one option is required")
    private List<VoteOptionRequest> options;
    
    @Schema(description = "開始時間；未指定為立即開始（未來時間即為 Scheduled）", nullable = true)
    private OffsetDateTime startTime;

    @NotNull(message = "End time is required")
    @Schema(description = "結束時間（必填，最長 365 天）", requiredMode = Schema.RequiredMode.REQUIRED)
    private OffsetDateTime endTime;

    @Schema(description = "已停用：Poll 只能單選，傳 true 回 400", deprecated = true)
    private boolean allowMultipleChoices = false;

    @Schema(description = "已停用：改用投票者可見度", deprecated = true)
    private boolean isAnonymous = false;

    @Schema(description = "投票者可見度；未指定為 OWNER", nullable = true)
    private VoterVisibility voterVisibility;

    @Size(max = 5, message = "Maximum 5 tags can be selected")
    private List<UUID> tagIds;

    @Data
    public static class VoteOptionRequest {
        
        @NotBlank(message = "Option text is required")
        @Size(max = 200, message = "Option text cannot exceed 200 characters")
        private String text;
        
        @Size(max = 500, message = "Option description cannot exceed 500 characters")
        private String description;
        
        private Integer displayOrder = 0;
    }
}