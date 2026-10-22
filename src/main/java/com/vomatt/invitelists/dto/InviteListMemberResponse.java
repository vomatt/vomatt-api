package com.vomatt.invitelists.dto;

import java.time.OffsetDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A member of an Invite List: a user added by ID, or an email address (never revealing whether it belongs to an account)")
public record InviteListMemberResponse(
        @Schema(description = "User ID of a member added by ID; always null for one added by email", example = "0199f2a1-2c5d-7f13-9b0e-7d4a1e3f6c82", nullable = true) String userId,
        @Schema(description = "Username of a member added by ID; always null for one added by email", example = "bob", nullable = true) String username,
        @Schema(description = "Avatar of a member added by ID as `bot:<kind>` (the bot cast); older accounts may hold an image URL. Null if never set, and always null for one added by email", example = "bot:boba", nullable = true) String avatarUrl,
        @Schema(description = "Email address a member was added by, for its whole life; null for one added by ID",
                example = "dave@example.com", nullable = true) String email,
        @Schema(description = "When the member was added", example = "2026-10-09T15:20:31+08:00") OffsetDateTime addedAt
) {
}
