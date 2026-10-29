package com.vomatt.admin.dto;

import java.time.OffsetDateTime;

import com.vomatt.entity.ReportReason;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A user's report of a Comment or Reply, with the Comment as it is now (deleted ones keep author and text)")
public record AdminCommentReportResponse(
        @Schema(description = "Report ID (UUIDv7)", example = "0199f4c2-1a2b-7c3d-8e4f-5a6b7c8d9e0f") String id,
        @Schema(description = "Reported Comment ID", example = "0199f3b1-2c4d-7e5f-8a9b-0c1d2e3f4a5b") String commentId,
        @Schema(description = "Poll the Comment belongs to", example = "0199f2a3-5b7e-7d40-a1c8-9e3b2f6d4c05") String voteId,
        @Schema(description = "Top-level Comment the reported Reply belongs to; null for a top-level Comment", nullable = true)
        String parentId,
        @Schema(description = "Comment text, also when deleted", example = "You are an idiot.") String commentText,
        @Schema(description = "Whether the Comment has since been deleted", example = "false") boolean commentDeleted,
        @Schema(description = "Comment author's user ID", example = "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d47") String authorId,
        @Schema(description = "Comment author's username", example = "troll") String authorUsername,
        @Schema(description = "Reporter's user ID", example = "0199c1a2-9e4f-7a21-8c3d-1b2a3c4d5e6f") String reporterId,
        @Schema(description = "Reporter's username", example = "alice") String reporterUsername,
        @Schema(description = "Reason given", example = "HARASSMENT") ReportReason reason,
        @Schema(description = "Reporter's note; null when none was given", example = "Insults me in every thread.", nullable = true)
        String note,
        @Schema(description = "When it was reported", example = "2026-10-26T10:00:00+08:00") OffsetDateTime createdAt
) {
}
