package com.vomatt.notifications.dto;

import java.time.OffsetDateTime;

import com.vomatt.entity.NotificationKind;
import com.vomatt.entity.VoteStatus;
import com.vomatt.users.dto.UserDto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "One notification of the feed. Which of `actor`, `poll` and `comment` are set depends on `kind`: "
        + "RESULT has `poll`; CANCELLED, INVITATION and CLOSING_SOON have `actor` (the Poll's owner) and `poll`; "
        + "FOLLOWED has `actor` (the new follower); COMMENT has `actor` (the author), `poll` and `comment`")
public record NotificationResponse(
        @Schema(description = "Notification id, opaque; pass it to `POST /api/notifications/{id}/read`",
                example = "comment_0199f2a5-1c2d-7e3f-8a4b-5c6d7e8f9a0b")
        String id,
        @Schema(description = "RESULT: a Poll you own, hold a Ballot in or are invited to has Ended. "
                + "CANCELLED: an Invite-only Poll you are invited to was Cancelled before it opened. "
                + "INVITATION: you are invited to a Scheduled or Open Invite-only Poll (replaced by RESULT once it Ends). "
                + "FOLLOWED: someone followed you. "
                + "COMMENT: someone commented on your Poll or replied to your Comment. "
                + "CLOSING_SOON: a Poll you are invited to, or a Public Poll of someone you follow, ends within 24 hours "
                + "and you hold no Ballot in it", example = "COMMENT")
        NotificationKind kind,
        @Schema(description = "When it happened: the Poll's end (RESULT, CANCELLED), the Invitation, the follow, the Comment, "
                + "or 24 hours before the Poll's end (CLOSING_SOON; the Poll's start if later). The feed is ordered by it, newest first",
                example = "2026-10-26T09:30:00+08:00")
        OffsetDateTime createdAt,
        @Schema(description = "Whether you marked it read, one by one or with read-all", example = "false")
        boolean read,
        @Schema(description = "Who did it, as public profile fields; null for RESULT", nullable = true)
        UserDto actor,
        @Schema(description = "The Poll it is about; null for FOLLOWED", nullable = true)
        PollSummary poll,
        @Schema(description = "The Comment or Reply; set only for COMMENT", nullable = true)
        CommentSummary comment
) {

    @Schema(description = "The Poll a notification is about. Carries no per-option counts, so it is the same whether or not results are Sealed")
    public record PollSummary(
            @Schema(description = "Poll id", example = "0199f2a3-5b7e-7d40-a1c8-9e3b2f6d4c05") String id,
            @Schema(description = "Poll title", example = "Best ramen in Taipei?") String title,
            @Schema(description = "When the Poll Ends (or Ended)", example = "2026-10-26T18:00:00+08:00") OffsetDateTime endTime,
            @Schema(description = "Poll status now", example = "OPEN") VoteStatus status,
            @Schema(description = "Turnout: number of Participants, visible at all times", example = "342") int turnout
    ) {}

    @Schema(description = "A Comment or Reply a notification is about")
    public record CommentSummary(
            @Schema(description = "Comment id", example = "0199f2a5-1c2d-7e3f-8a4b-5c6d7e8f9a0b") String id,
            @Schema(description = "Start of the text, whitespace collapsed, at most 120 characters plus an ellipsis",
                    example = "Tonkotsu at Nagi, and it is not close") String excerpt
    ) {}
}
