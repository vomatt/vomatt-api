package com.vomatt.votes.dto;

import com.vomatt.tags.dto.TagDto;

import java.time.OffsetDateTime;
import java.util.List;

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