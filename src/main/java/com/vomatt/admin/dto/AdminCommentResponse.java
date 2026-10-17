package com.vomatt.admin.dto;

import java.time.OffsetDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A Comment or Reply as seen in the back office; deleted ones keep their author and text")
public record AdminCommentResponse(
        @Schema(description = "Comment ID (UUIDv7)", example = "0199f3b1-2c4d-7e5f-8a9b-0c1d2e3f4a5b") String id,
        @Schema(description = "Poll the Comment belongs to", example = "0199f2a3-5b7e-7d40-a1c8-9e3b2f6d4c05") String voteId,
        @Schema(description = "Title of that Poll", example = "Which language should we use for the next side project?") String voteTitle,
        @Schema(description = "Top-level Comment this Reply belongs to; null for a top-level Comment", nullable = true) String parentId,
        @Schema(description = "Author's user ID", example = "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d47") String userId,
        @Schema(description = "Author's username", example = "alice") String username,
        @Schema(description = "Comment text, also when deleted", example = "Java, obviously.") String content,
        @Schema(description = "Whether the Comment is deleted", example = "false") boolean deleted,
        @Schema(description = "When it was written", example = "2026-10-16T09:30:00+08:00") OffsetDateTime createdAt,
        @Schema(description = "When it was last edited or deleted", example = "2026-10-16T09:30:00+08:00") OffsetDateTime updatedAt
) {
}
