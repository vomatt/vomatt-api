package com.vomatt.admin.dto;

import java.time.OffsetDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Feedback as seen in the back office")
public record AdminFeedbackResponse(
        @Schema(description = "Feedback ID (UUIDv7)", example = "0199c3a2-7b5e-7c1d-9a4f-3e2b1d5c6f70") String id,
        @Schema(description = "Sender's user ID", example = "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d47") String userId,
        @Schema(description = "Sender's username", example = "alice") String username,
        @Schema(description = "Feedback text", example = "Please add dark mode.") String content,
        @Schema(description = "When it was sent", example = "2026-10-23T10:00:00+08:00") OffsetDateTime createdAt
) {
}
