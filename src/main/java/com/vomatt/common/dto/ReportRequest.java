package com.vomatt.common.dto;

import com.vomatt.entity.ReportReason;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "Why the caller reports a Poll, Comment or Reply")
public record ReportRequest(
        @Schema(description = "Reason", example = "SPAM", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Report reason is required")
        ReportReason reason,
        @Schema(description = "Optional details for the moderators, max 500 characters; blank is stored as none",
                example = "Posts the same link in every thread.", nullable = true)
        @Size(max = 500, message = "Report note cannot exceed 500 characters")
        String note
) {
    public ReportRequest {
        note = note == null || note.isBlank() ? null : note.strip();
    }
}
