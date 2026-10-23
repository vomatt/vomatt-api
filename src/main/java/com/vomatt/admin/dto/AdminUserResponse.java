package com.vomatt.admin.dto;

import java.time.OffsetDateTime;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "An account as listed in the back office. Contact details are masked and names and birthday left out: the list is for finding an account, `GET /api/admin/users/{userId}` holds the full record")
public record AdminUserResponse(
        @Schema(description = "User ID (UUIDv7)", example = "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d47") String id,
        @Schema(description = "Username", example = "alice") String username,
        @Schema(description = "Display name; null when unset", example = "Alice", nullable = true) String displayName,
        @Schema(description = "Email, masked to its first character and domain; null for accounts registered by phone", example = "a***@example.com", nullable = true) String email,
        @Schema(description = "Phone number (E.164), masked to its first 3 characters and last 2 digits; null for accounts registered by email or OAuth", example = "+88****78", nullable = true) String phoneNumber,
        @Schema(description = "How the account signs in: email_otp, phone_otp, google, line or apple", example = "email_otp", nullable = true) String authMethod,
        @Schema(description = "Roles: user, moderator, admin", example = "[\"user\"]") List<String> roles,
        @Schema(description = "False when suspended", example = "true") boolean active,
        @Schema(description = "Last sign-in; null if never", example = "2026-10-16T21:04:11+08:00", nullable = true) OffsetDateTime lastLoginAt,
        @Schema(description = "When the account was created", example = "2026-09-01T10:00:00+08:00") OffsetDateTime createdAt
) {
}
