package com.vomatt.votes.dto;

import com.vomatt.tags.dto.TagDto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonInclude;
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

    /** People who hold a ballot (distinct voters); visible in every state. */
    private long participantCount;

    /** Non-deleted comments. */
    private long commentCount;

    /**
     * The signed-in viewer's option: absent for guests, null when the viewer hasn't voted.
     * Optional.empty() serialises as null; a null field is left out.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Optional<String> myOptionId;
    
    @Data
    public static class VoteOptionResponse {
        private String id;
        private String text;
        private String description;
        private Integer displayOrder;
        private OffsetDateTime createdAt;
        /** Withheld (left out) until the vote has ended, so the running count can't sway voters. */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private Long votes;
    }
}