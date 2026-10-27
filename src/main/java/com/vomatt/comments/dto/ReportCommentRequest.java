package com.vomatt.comments.dto;

import com.vomatt.entity.CommentReportReason;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "Why the caller reports a Comment or Reply")
public record ReportCommentRequest(
        @Schema(description = "Reason", example = "HARASSMENT", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Report reason is required")
        CommentReportReason reason,
        @Schema(description = "Optional details for the moderators, max 500 characters", example = "Insults me in every thread.",
                nullable = true)
        @Size(max = 500, message = "Report note cannot exceed 500 characters")
        String note
) {}
