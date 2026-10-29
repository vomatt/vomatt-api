package com.vomatt.votes.dto;

import com.vomatt.entity.Audience;
import com.vomatt.entity.VoterVisibility;
import com.vomatt.tags.dto.TagDto;

import java.time.OffsetDateTime;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "A Poll. Its state is derived from `startTime`, `endTime` and `votingActive`: Scheduled (now < startTime), "
        + "Open (`votingActive` = true), Ended (now >= endTime). A Cancelled Poll (no longer produced) is Ended with `endTime` <= `startTime`. "
        + "While the Poll has not Ended, per-option counts are Sealed (`options[].votes` is null)")
public class VoteResponse {
    
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

    @Schema(description = "Avatar of the owner as `bot:<kind>` (the bot cast). Older accounts may hold an image URL; null if never set",
            example = "bot:boba", nullable = true)
    private String creatorAvatarUrl;

    @Schema(description = "When the Poll opens (or opened)", example = "2026-10-12T09:00:00+08:00")
    private OffsetDateTime startTime;

    @Schema(description = "When the Poll ends (or actually ended). Equals `deadline` unless the Poll ended early: then it is the moment it Ended", example = "2026-10-19T09:00:00+08:00")
    private OffsetDateTime endTime;

    @Schema(description = "Deadline: the end time the owner set. Ending early never moves it, so `endTime` < `deadline` means the Poll ended early. "
            + "For Polls that ended early before this field existed, it equals `endTime`", example = "2026-10-19T09:00:00+08:00")
    private OffsetDateTime deadline;

    @Schema(description = "False once the owner has Closed the Poll early (or, for legacy data, Cancelled it while Scheduled); true otherwise, including after the end time passed naturally. "
            + "Do not use it for state: read `votingActive` and the times instead", example = "true")
    private boolean isActive;

    @Schema(description = "Deprecated: always false, Polls are single-choice", deprecated = true, example = "false")
    private boolean allowMultipleChoices;

    @Schema(description = "Deprecated: use `voterVisibility` instead; true exactly when Voter Visibility is NOBODY", deprecated = true, example = "false")
    private boolean isAnonymous;

    @Schema(description = "When the Poll was created", example = "2026-10-09T15:20:31+08:00")
    private OffsetDateTime createdAt;

    @Schema(description = "When the Poll was last updated", example = "2026-10-09T15:20:31+08:00")
    private OffsetDateTime updatedAt;

    @Schema(description = "Deprecated alias of `participantCount` (Polls are single-choice); always visible", deprecated = true, example = "42")
    private long totalVotes;

    @Schema(description = "Turnout: number of Participants. Always visible, including while the results are Sealed", example = "42")
    private long participantCount;

    @Schema(description = "True only while the Poll is Open, i.e. when Ballots can be cast, changed or retracted. "
            + "False when Scheduled or Ended", example = "true")
    private boolean isVotingActive;

    @Schema(description = "Options sorted by `displayOrder`")
    private List<VoteOptionResponse> options;

    @Schema(description = "Tags attached to the Poll; empty when it has none")
    private List<TagDto> tags;

    @Schema(description = "Number of Comments and Replies on the Poll, excluding deleted ones", example = "7")
    private long commentCount;

    @Schema(description = "Number of distinct users who shared the Poll through its share button (`POST /api/votes/{voteId}/share`); "
            + "repeat shares by the same user count once. Signed-out shares are not counted", example = "18")
    private long shareCount;

    @Schema(description = "Who may see which option each Participant chose once the Poll has Ended: NOBODY, OWNER or SIGNED_IN (anyone who can see the Poll). "
            + "Visible at all times so Participants know it before casting a Ballot; fixed once the Poll is Open", example = "OWNER")
    private VoterVisibility voterVisibility;

    @Schema(description = "Who can reach the Poll: PUBLIC (listed in Feed / Explore / Search and the owner's profile), UNLISTED "
            + "(reachable only through its link) or INVITE_ONLY (only the owner and its Invitees). Fixed once the Poll is Open", example = "PUBLIC")
    private Audience audience;

    @Schema(description = "Number of Invitees of an INVITE_ONLY Poll, shown next to Turnout to everyone who can see the Poll; "
            + "null for other Audiences", example = "12", nullable = true)
    private Long inviteeCount;

    @Schema(description = "Whether the caller's current notification for this Poll is unread. Only set on My Polls; null on every other endpoint. "
            + "On `status=open`: true while the caller has not read their Invitation to this INVITE_ONLY Poll, false otherwise "
            + "(including Polls the caller owns or only holds a Ballot in). On `status=ended`: true while the Ended Notification "
            + "is unread (false for a Cancelled Poll, which sends none); reading the Invitation earlier does not read it", example = "true", nullable = true)
    private Boolean unread;

    @Schema(description = "ID of the option in the caller's Ballot. Null when the caller is not signed in, holds no Ballot in this Poll, "
            + "on the create response, or when the caller's Anonymous Ballot was sealed after the Poll Ended (within a day of the end; "
            + "`myBallotAnonymous` still says the caller took part)", example = "0199f2a4-8d11-7c3e-b0a4-5e29c7d6f813", nullable = true)
    private String myOptionId;

    @Schema(description = "Whether the caller's Ballot is Anonymous. Null when the caller holds no Ballot (or is not signed in); "
            + "still set when `myOptionId` is null because the caller's Anonymous Ballot was sealed", example = "false", nullable = true)
    private Boolean myBallotAnonymous;

    @Schema(description = "Whether the caller (the owner) can still Delete the Poll: while Scheduled, or within the delete window after posting "
            + "(default 20 minutes, configurable). Set on every Poll response to the owner; null when the caller is not the owner or not signed in",
            example = "true", nullable = true)
    private Boolean deletable;

    @Schema(description = "Whether the caller bookmarked the Poll (`PUT /api/votes/{voteId}/bookmark`). Null when the caller is not signed in "
            + "and in admin views", example = "false", nullable = true)
    private Boolean bookmarked;

    @Data
    @Schema(description = "One option of a Poll")
    public static class VoteOptionResponse {
        @Schema(description = "Option ID (UUIDv7); send it in the cast Ballot request", example = "0199f2a4-8d11-7c3e-b0a4-5e29c7d6f813")
        private String id;

        @Schema(description = "Option label", example = "Java")
        private String text;

        @Schema(description = "Optional detail for the option; null when none was given", example = "Virtual threads are great", nullable = true)
        private String description;

        @Schema(description = "Sort position (ascending)", example = "0")
        private Integer displayOrder;

        @Schema(description = "When the option was created", example = "2026-10-09T15:20:31+08:00")
        private OffsetDateTime createdAt;

        @Schema(description = "Number of Ballots that chose this option. Null while the Poll has not Ended (Sealed), for everyone including the owner; "
                + "a number once it has Ended", example = "27", nullable = true)
        private Long votes;
    }
}