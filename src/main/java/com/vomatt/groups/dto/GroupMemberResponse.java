package com.vomatt.groups.dto;

import java.time.OffsetDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A member of a Group: a user added by ID, or an email address (never revealing whether it belongs to an account)")
public record GroupMemberResponse(
        @Schema(description = "User ID of a member added by ID; always null for one added by email", example = "0199f2a1-2c5d-7f13-9b0e-7d4a1e3f6c82", nullable = true) String userId,
        @Schema(description = "Username of a member added by ID; always null for one added by email", example = "bob", nullable = true) String username,
        @Schema(description = "Email address a member was added by, for its whole life; null for one added by ID",
                example = "dave@example.com", nullable = true) String email,
        @Schema(description = "When the member was added", example = "2026-10-09T15:20:31+08:00") OffsetDateTime addedAt
) {
}
