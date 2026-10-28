package com.vomatt.users.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Everything the service holds about the caller, for a data-portability request; of other users it carries only the id
 * and username of those the caller follows or blocked.
 */
@Schema(description = "Export of the caller's own data: profile, preferences, Polls created, Comments written, Ballots cast, feedback sent, follows, blocks and bookmarks")
public record UserDataExportResponse(
        @Schema(description = "When the export was produced", example = "2026-10-23T10:00:00+08:00") OffsetDateTime exportedAt,
        @Schema(description = "The profile as returned by GET /api/users/me") MyProfileResponse profile,
        @Schema(description = "Stored preferences by key (visibility settings, Ballot default)", example = "{\"visibility.location\": \"true\"}") Map<String, String> preferences,
        @Schema(description = "Polls the caller created, newest first, Deleted ones included") List<PollExport> polls,
        @Schema(description = "Comments the caller wrote that are not deleted") List<CommentExport> comments,
        @Schema(description = "Ballots the caller holds, oldest first") List<BallotExport> ballots,
        @Schema(description = "Feedback the caller sent, oldest first") List<FeedbackExport> feedback,
        @Schema(description = "Users the caller follows, oldest first, suspended ones included") List<RelationExport> following,
        @Schema(description = "Users the caller blocked, oldest first, suspended ones included") List<RelationExport> blocking,
        @Schema(description = "Polls the caller bookmarked, oldest first") List<BookmarkExport> bookmarks
) {
    @Schema(description = "A Poll the caller created")
    public record PollExport(
            @Schema(example = "0199f2a3-5b7e-7d40-a1c8-9e3b2f6d4c05") String id,
            @Schema(example = "Lunch?") String title,
            @Schema(nullable = true) String description,
            @Schema(description = "PUBLIC, UNLISTED or INVITE_ONLY", example = "PUBLIC") String audience,
            @Schema(nullable = true) OffsetDateTime startTime,
            @Schema(nullable = true) OffsetDateTime endTime,
            OffsetDateTime createdAt,
            @Schema(description = "Whether the caller deleted it") boolean deleted,
            @Schema(description = "Option texts in display order") List<String> options
    ) {}

    @Schema(description = "A Comment the caller wrote")
    public record CommentExport(
            @Schema(example = "0199f2a5-1c2d-7e3f-8a4b-5c6d7e8f9a0b") String id,
            @Schema(description = "Poll the Comment belongs to", example = "0199f2a3-5b7e-7d40-a1c8-9e3b2f6d4c05") String voteId,
            String text,
            OffsetDateTime createdAt
    ) {}

    @Schema(description = "A Ballot the caller cast")
    public record BallotExport(
            @Schema(example = "0199f2a3-5b7e-7d40-a1c8-9e3b2f6d4c05") String voteId,
            @Schema(example = "Lunch?") String voteTitle,
            @Schema(description = "Chosen option; null once an Anonymous Ballot of an Ended Poll was sealed", nullable = true) String optionId,
            @Schema(nullable = true) String optionText,
            boolean anonymous,
            OffsetDateTime castAt
    ) {}

    @Schema(description = "Feedback the caller sent")
    public record FeedbackExport(
            @Schema(example = "0199c3a2-7b5e-7c1d-9a4f-3e2b1d5c6f70") String id,
            @Schema(example = "Please add dark mode.") String content,
            OffsetDateTime createdAt
    ) {}

    @Schema(description = "A user the caller follows or blocked")
    public record RelationExport(
            @Schema(example = "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d47") String userId,
            @Schema(example = "bob") String username,
            @Schema(description = "When the follow or block was added") OffsetDateTime since
    ) {}

    @Schema(description = "A Poll the caller bookmarked")
    public record BookmarkExport(
            @Schema(example = "0199f2a3-5b7e-7d40-a1c8-9e3b2f6d4c05") String voteId,
            OffsetDateTime createdAt
    ) {}
}
