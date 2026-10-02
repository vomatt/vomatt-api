package com.vomatt.votes.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
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
    
    private OffsetDateTime startTime;
    
    private OffsetDateTime endTime;
    
    private boolean allowMultipleChoices = false;
    
    private boolean isAnonymous = false;

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