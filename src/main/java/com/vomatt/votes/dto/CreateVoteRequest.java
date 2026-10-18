package com.vomatt.votes.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import com.vomatt.entity.Audience;
import com.vomatt.entity.User;
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
    private List<@Valid VoteOptionRequest> options;
    
    @Schema(description = "When the Poll opens (ISO-8601 with offset). Null on create means open immediately; "
            + "a future time makes the Poll Scheduled. Null on edit keeps the current start time. Must be before `endTime`",
            example = "2026-10-12T09:00:00+08:00", nullable = true)
    private OffsetDateTime startTime;

    @NotNull(message = "End time is required")
    @Schema(description = "When the Poll ends (ISO-8601 with offset); also sets the Deadline. Required; must be in the future, after `startTime`, "
            + "and at most 365 days after the start time",
            example = "2026-10-19T09:00:00+08:00", requiredMode = Schema.RequiredMode.REQUIRED)
    private OffsetDateTime endTime;

    @Schema(description = "Deprecated: Polls are single-choice. Sending true is rejected with 400 `vote.multiple.not_allowed`",
            deprecated = true, example = "false")
    private boolean allowMultipleChoices = false;

    @Schema(description = "Deprecated: use `voterVisibility` instead. True with no `voterVisibility` means NOBODY. "
            + "Null keeps false on create and the current value on edit", deprecated = true, example = "false", nullable = true)
    private Boolean anonymous;

    @Schema(description = "Who may see which option each Participant chose once the Poll has Ended: NOBODY (not even the owner), "
            + "OWNER (default) or SIGNED_IN (anyone who can see the Poll). Null keeps the default OWNER on create and the current value on edit. "
            + "Participants see this level before casting a Ballot",
            example = "OWNER", nullable = true)
    private VoterVisibility voterVisibility;

    @Schema(description = "Who can reach the Poll: PUBLIC (default; listed in Feed / Explore / Search and the owner's profile), "
            + "UNLISTED (reachable only through its link, listed only in the owner's My Polls) or INVITE_ONLY (only the owner "
            + "and `inviteeIds` can see it). Null keeps PUBLIC on create and the current value on edit",
            example = "PUBLIC", nullable = true)
    private Audience audience;

    @Schema(description = "User IDs to invite; only for an INVITE_ONLY Poll (otherwise 400 `vote.invitee.not_invite_only`). "
            + "At most 100 IDs; null elements are rejected. Duplicates and the owner's own ID are ignored; unknown or suspended users are rejected with 400 `vote.invitee.ids_invalid`. "
            + "On edit the list replaces the current Invitees; null keeps them (and an Audience other than INVITE_ONLY removes them)",
            example = "[\"0199f2a1-2c5d-7f13-9b0e-7d4a1e3f6c82\"]", nullable = true)
    @Size(max = 100, message = "Maximum 100 users can be invited at once")
    private List<@NotNull(message = "Invitee ID cannot be null") UUID> inviteeIds;

    @Schema(description = "Email addresses to invite, for people who may not have an account yet; only for an INVITE_ONLY Poll "
            + "(otherwise 400 `vote.invitee.not_invite_only`). At most 100; null elements and invalid addresses are rejected. "
            + "Addresses are normalized like sign-in does: lower-cased, trimmed, Gmail in canonical form; duplicates and the owner's own address are ignored. "
            + "Each address is listed as entered and gets an invitation email; an active account's user gets access right away, "
            + "any other address once someone verifies it at sign-up / sign-in. "
            + "On edit the list replaces the Invitees added by email (matched by address); null keeps them",
            example = "[\"carol@example.com\"]", nullable = true)
    @Size(max = 100, message = "Maximum 100 email addresses can be invited at once")
    private List<@NotNull(message = "Invitee email cannot be null") @Email(message = "Invalid invitee email") @Size(max = 254, message = "Invitee email cannot exceed 254 characters") String> inviteeEmails;

    // Normalized like sign-in before validation, so padded or upper-case input is accepted and stored the same way
    public void setInviteeEmails(List<String> emails) {
        this.inviteeEmails = emails == null ? null : emails.stream().map(e -> e == null ? null : User.normalizeEmail(e)).toList();
    }

    @Schema(description = "IDs of the caller's Groups (see `GET /api/groups`) whose current members to invite; only for an INVITE_ONLY Poll "
            + "(otherwise 400 `vote.invitee.not_invite_only`). At most 20; null elements are rejected; a missing Group or someone else's is 404 `group.not_found`. "
            + "Members added by ID join `inviteeIds` (suspended ones are skipped) and members added by email join `inviteeEmails`, by their stored address, "
            + "with the same rules and invitation emails. The members are copied now: later Group changes never touch the Poll. "
            + "On edit they are merged into whichever of `inviteeIds` / `inviteeEmails` is set, so an explicit `[]` plus `groupIds` replaces that kind of Invitee "
            + "with the Groups' members (already-invited members are kept as they are); a null list keeps that kind of Invitee and adds the Groups' members on top",
            example = "[\"0199f2b0-7a1c-7e45-9b3d-2c8f6a4e1d07\"]", nullable = true)
    @Size(max = 20, message = "Maximum 20 groups can be invited at once")
    private List<@NotNull(message = "Group ID cannot be null") UUID> groupIds;

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

    /** {@code voterVisibility}, or NOBODY for a legacy client that only sends {@code anonymous: true}; null keeps the default / current value. */
    public VoterVisibility effectiveVoterVisibility() {
        if (voterVisibility != null) {
            return voterVisibility;
        }
        return Boolean.TRUE.equals(anonymous) ? VoterVisibility.NOBODY : null;
    }
}
