package com.vomatt.votes.dto;

import java.time.OffsetDateTime;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Results of an Ended Poll. Only returned once the Poll has Ended; before that the endpoint answers 403 `vote.results.sealed`")
public class VoteResultResponse {
    
    @Schema(description = "Poll ID (UUIDv7)", example = "0199f2a3-5b7e-7d40-a1c8-9e3b2f6d4c05")
    private String id;

    @Schema(description = "Poll question", example = "Which language should we use for the next side project?")
    private String title;

    @Schema(description = "Optional context for the Poll; null when the owner gave none", example = "Vote before Friday.", nullable = true)
    private String description;

    @Schema(description = "User ID of the owner", example = "0199f2a1-1b4c-7e02-8a9d-6c3f0d2e5b71")
    private String creatorId;

    @Schema(description = "Username of the owner", example = "alice")
    private String creatorUsername;

    @Schema(description = "When the Poll opened", example = "2026-10-12T09:00:00+08:00")
    private OffsetDateTime startTime;

    @Schema(description = "When the Poll ended", example = "2026-10-19T09:00:00+08:00")
    private OffsetDateTime endTime;

    @Schema(description = "False when the owner Closed the Poll early or cancelled it; true otherwise", example = "true")
    private boolean isActive;

    @Schema(description = "Deprecated: always false, Polls are single-choice", deprecated = true, example = "false")
    private boolean allowMultipleChoices;

    @Schema(description = "Deprecated: use `voterVisibility` on the Poll instead", deprecated = true, example = "false")
    private boolean isAnonymous;

    @Schema(description = "When the Poll was created", example = "2026-10-09T15:20:31+08:00")
    private OffsetDateTime createdAt;

    @Schema(description = "Deprecated alias of `totalParticipants`", deprecated = true, example = "42")
    private long totalVotes;

    @Schema(description = "Turnout: number of Participants, the denominator of every option's Support", example = "42")
    private long totalParticipants;

    @Schema(description = "Always false for an Ended Poll", example = "false")
    private boolean isVotingActive;

    @Schema(description = "Options sorted by `displayOrder`, each with its count and Support")
    private List<VoteOptionResultResponse> options;
    
    @Data
    @Schema(description = "One option of an Ended Poll with its result")
    public static class VoteOptionResultResponse {
        @Schema(description = "Option ID (UUIDv7)", example = "0199f2a4-8d11-7c3e-b0a4-5e29c7d6f813")
        private String id;

        @Schema(description = "Option label", example = "Java")
        private String text;

        @Schema(description = "Optional detail for the option; null when none was given", example = "Virtual threads are great", nullable = true)
        private String description;

        @Schema(description = "Sort position (ascending)", example = "0")
        private Integer displayOrder;

        @Schema(description = "Number of Ballots that chose this option", example = "27")
        private long voteCount;

        @Schema(description = "Support: share of the Participants whose Ballot chose this option, 0-100 (not rounded). 0 when there are no Participants",
                example = "64.28571428571429")
        private double percentage;
    }

}