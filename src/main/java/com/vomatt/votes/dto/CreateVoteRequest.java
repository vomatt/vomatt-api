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
@Schema(description = "Body to create a Poll, or to replace a Scheduled Poll's content (PUT sends the full body again)")
public class CreateVoteRequest {
    
    @NotBlank(message = "Title is required")
    @Size(max = 200, message = "Title cannot exceed 200 characters")
    @Schema(description = "Poll question, 1-200 characters", example = "Which language should we use for the next side project?",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String title;
    
    @Size(max = 1000, message = "Description cannot exceed 1000 characters")
    @Schema(description = "Optional context for the Poll, up to 1000 characters; may be null", example = "Vote before Friday.",
            nullable = true)
    private String description;
    
    @NotEmpty(message = "At least one option is required")
    @Schema(description = "Options of the Poll; the number of options must be between 2 and 10 (`vote.options.min` / `vote.options.max`)",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private List<VoteOptionRequest> options;
    
    @Schema(description = "When the Poll opens (ISO-8601 with offset). Null on create means open immediately; "
            + "a future time makes the Poll Scheduled. Null on edit keeps the current start time. Must be before `endTime`",
            example = "2026-10-12T09:00:00+08:00", nullable = true)
    private OffsetDateTime startTime;

    @NotNull(message = "End time is required")
    @Schema(description = "When the Poll ends (ISO-8601 with offset). Required; must be in the future, after `startTime`, "
            + "and at most 365 days after the start time",
            example = "2026-10-19T09:00:00+08:00", requiredMode = Schema.RequiredMode.REQUIRED)
    private OffsetDateTime endTime;

    @Schema(description = "Deprecated: Polls are single-choice. Sending true is rejected with 400 `vote.multiple.not_allowed`",
            deprecated = true, example = "false")
    private boolean allowMultipleChoices = false;

    @Schema(description = "Deprecated: use `voterVisibility` instead", deprecated = true, example = "false")
    private boolean isAnonymous = false;

    @Schema(description = "Who may see which option each Participant chose once the Poll has Ended: NOBODY (not even the owner), "
            + "OWNER (default) or SIGNED_IN (any signed-in user). Null keeps the default OWNER on create and the current value on edit. "
            + "Participants see this level before casting a Ballot",
            example = "OWNER", nullable = true)
    private VoterVisibility voterVisibility;

    @Size(max = 5, message = "Maximum 5 tags can be selected")
    @Schema(description = "IDs of up to 5 existing tags (see `GET /api/tags`); null or empty means no tags. Unknown IDs are rejected with 400 `tag.ids.invalid`",
            example = "[\"0199f2a4-6c3e-7a1b-9d52-3f8e1b7c4a10\"]", nullable = true)
    private List<UUID> tagIds;

    @Data
    @Schema(description = "One option of a Poll")
    public static class VoteOptionRequest {
        
        @NotBlank(message = "Option text is required")
        @Size(max = 200, message = "Option text cannot exceed 200 characters")
        @Schema(description = "Option label, 1-200 characters", example = "Java", requiredMode = Schema.RequiredMode.REQUIRED)
        private String text;
        
        @Size(max = 500, message = "Option description cannot exceed 500 characters")
        @Schema(description = "Optional detail for the option, up to 500 characters; may be null", example = "Virtual threads are great", nullable = true)
        private String description;
        
        @Schema(description = "Sort position within the Poll (ascending). Null falls back to the option's position in the array; "
                + "defaults to 0 when the field is omitted", example = "0", nullable = true)
        private Integer displayOrder = 0;
    }
}