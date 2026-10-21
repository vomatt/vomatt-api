package com.vomatt.admin.dto;

import java.time.OffsetDateTime;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "An account with its activity counts, as shown in the back office")
public record AdminUserDetail(
        @Schema(description = "User ID (UUIDv7)", example = "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d47") String id,
        @Schema(description = "Username", example = "alice") String username,
        @Schema(description = "Display name; null when unset", example = "Alice", nullable = true) String displayName,
        @Schema(description = "Email; null for accounts registered by phone", example = "alice@example.com", nullable = true) String email,
        @Schema(description = "Phone number (E.164); null for accounts registered by email or OAuth", example = "+886912345678", nullable = true) String phoneNumber,
        @Schema(description = "How the account signs in: email_otp, phone_otp, google, line or apple", example = "email_otp", nullable = true) String authMethod,
        @Schema(description = "Roles: user, moderator, admin", example = "[\"user\"]") List<String> roles,
        @Schema(description = "False when suspended", example = "true") boolean active,
        @Schema(description = "Last sign-in; null if never", example = "2026-10-16T21:04:11+08:00", nullable = true) OffsetDateTime lastLoginAt,
        @Schema(description = "Points balance", example = "120") int points,
        @Schema(description = "Membership level: BASIC, SILVER, GOLD or PLATINUM", example = "BASIC") String membershipLevel,
        @Schema(description = "When the account was created", example = "2026-09-01T10:00:00+08:00") OffsetDateTime createdAt,
        @Schema(description = "Polls the user created, any Audience, Deleted ones included", example = "8") long pollCount,
        @Schema(description = "Ballots the user currently holds", example = "35") long ballotCount,
        @Schema(description = "Comments and Replies the user wrote, deleted ones included", example = "14") long commentCount,
        @Schema(description = "Users this user blocks", example = "2") long blockingCount,
        @Schema(description = "Users who block this user", example = "1") long blockedByCount
) {
}
