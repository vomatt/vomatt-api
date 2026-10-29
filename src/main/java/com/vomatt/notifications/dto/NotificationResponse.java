package com.vomatt.notifications.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.vomatt.entity.NotificationCategory;
import com.vomatt.entity.NotificationKind;
import com.vomatt.entity.VoteStatus;
import com.vomatt.users.dto.FollowUserDto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "One notification of the feed. Which of `actor`, `poll` and `comment` are set depends on `kind`: "
        + "RESULT has `poll`; CANCELLED, INVITATION and CLOSING_SOON have `actor` (the Poll's owner) and `poll`; "
        + "FOLLOWED has `actor` (the new follower); COMMENT has `actor` (the author), `poll` and `comment`; "
        + "LIKE has `actor` (who liked), `poll` and `comment` (your liked Comment)")
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
                + "and you hold no Ballot in it. "
                + "LIKE: someone liked your Comment or Reply", example = "COMMENT")
        NotificationKind kind,
        @Schema(description = "The tab it is filed under, fixed by `kind`: CONVERSATION (COMMENT), LIKE (LIKE), "
                + "POLL_END (RESULT, CANCELLED, CLOSING_SOON), INVITATION (INVITATION), FOLLOW (FOLLOWED). "
                + "Filter the feed with `GET /api/notifications?category=`", example = "CONVERSATION")
        NotificationCategory category,
        @Schema(description = "When it happened: the Poll's end (RESULT, CANCELLED), the Invitation, the follow, the Comment, the like, "
                + "or 24 hours before the Poll's end (CLOSING_SOON; the Poll's start if later). The feed is ordered by it, newest first",
                example = "2026-10-26T09:30:00+08:00")
        OffsetDateTime createdAt,
        @Schema(description = "Whether you marked it read, one by one or with read-all", example = "false")
        boolean read,
        @Schema(description = "Who did it, as public profile fields with the caller's relation to them; null for RESULT",
                nullable = true)
        FollowUserDto actor,
        @Schema(description = "The Poll it is about; null for FOLLOWED", nullable = true)
        PollSummary poll,
        @Schema(description = "The Comment or Reply (for LIKE, yours that was liked); set only for COMMENT and LIKE", nullable = true)
        CommentSummary comment
) {

    @Schema(description = "The Poll a notification is about. Carries no per-option counts; the outcome fields are null "
            + "until the Poll has Ended, so Sealed results stay hidden")
    public record PollSummary(
            @Schema(description = "Poll id", example = "0199f2a3-5b7e-7d40-a1c8-9e3b2f6d4c05") String id,
            @Schema(description = "Poll title", example = "Best ramen in Taipei?") String title,
            @Schema(description = "When the Poll opens (or opened)", example = "2026-10-20T09:00:00+08:00") OffsetDateTime startTime,
            @Schema(description = "When the Poll Ends (or Ended)", example = "2026-10-26T18:00:00+08:00") OffsetDateTime endTime,
            @Schema(description = "Poll status now", example = "OPEN") VoteStatus status,
            @Schema(description = "Turnout: number of Participants, visible at all times", example = "342") int turnout,
            @Schema(description = "Option of the caller's Ballot; null without a Ballot, or once an Anonymous Ballot of an "
                    + "Ended Poll is sealed", example = "0199f2a4-8d11-7c3e-b0a4-5e29c7d6f813", nullable = true)
            String myOptionId,
            @ArraySchema(arraySchema = @Schema(description = "Ended only: ids of the option(s) with the most Ballots, in "
                    + "display order; more than one is a tie, empty when no Ballot named an option. Null until the Poll has Ended",
                    nullable = true), schema = @Schema(example = "0199f2a4-8d11-7c3e-b0a4-5e29c7d6f813"))
            List<String> winningOptionIds,
            @ArraySchema(arraySchema = @Schema(description = "Ended only: texts of the options in `winningOptionIds`, same "
                    + "order. Null until the Poll has Ended", nullable = true), schema = @Schema(example = "Nagi"))
            List<String> winningOptionLabels,
            @Schema(description = "Ended only: whether more than one option shares the most Ballots. Null until the Poll has Ended",
                    example = "false", nullable = true)
            Boolean tie
    ) {}

    @Schema(description = "A Comment or Reply a notification is about")
    public record CommentSummary(
            @Schema(description = "Comment id", example = "0199f2a5-1c2d-7e3f-8a4b-5c6d7e8f9a0b") String id,
            @Schema(description = "For a Reply, the id of the Comment it is under (link it as `<parentId>/<id>`); null for a "
                    + "top-level Comment", example = "0199f2a4-ff01-7a2b-9c3d-4e5f6a7b8c9d", nullable = true)
            String parentId,
            @Schema(description = "Start of the text, whitespace collapsed, at most 120 characters plus an ellipsis",
                    example = "Tonkotsu at Nagi, and it is not close") String excerpt
    ) {}
}
