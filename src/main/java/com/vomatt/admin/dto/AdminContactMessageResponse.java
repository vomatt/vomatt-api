package com.vomatt.admin.dto;

import java.time.OffsetDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Contact form message as seen in the back office")
public record AdminContactMessageResponse(
        @Schema(description = "Message ID (UUIDv7)", example = "0199c3a2-7b5e-7c1d-9a4f-3e2b1d5c6f70") String id,
        @Schema(description = "Sender's name", example = "Alice Chen") String name,
        @Schema(description = "Sender's reply-to email address", example = "alice@example.com") String email,
        @Schema(description = "The question", example = "Can my school use Vomatt for student council elections?") String message,
        @Schema(description = "Language of the page the visitor wrote from", allowableValues = {"en", "zh"}, example = "en") String locale,
        @Schema(description = "When it was sent", example = "2026-11-07T10:00:00+08:00") OffsetDateTime createdAt
) {
}
