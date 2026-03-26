package com.vomattapi.application.dto.vote;

import com.vomattapi.application.dto.common.BaseResponse;
import com.vomattapi.application.dto.tag.TagDto;

import java.time.LocalDateTime;
import java.util.List;

import lombok.Data;

@Data
public class VoteResponse extends BaseResponse {
    
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
    private LocalDateTime updatedAt;
    private long totalVotes;
    private boolean isVotingActive;
    private List<VoteOptionResponse> options;
    private List<TagDto> tags;
    
    @Data
    public static class VoteOptionResponse {
        private String id;
        private String text;
        private String description;
        private Integer displayOrder;
        private LocalDateTime createdAt;
        private long votes;
    }
}