package com.vomatt.admin.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "An account as listed in the back office")
public record AdminUserResponse(
        @Schema(description = "User ID (UUIDv7)", example = "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d47") String id,
        @Schema(description = "Username", example = "alice") String username,
        @Schema(description = "Display name; null when unset", example = "Alice", nullable = true) String displayName,
        @Schema(description = "First name; null when unset", example = "Alice", nullable = true) String firstName,
        @Schema(description = "Last name; null when unset", example = "Chen", nullable = true) String lastName,
        @Schema(description = "Birthday (ISO date), shown regardless of the user's visibility settings; null when unset", example = "1995-04-12", nullable = true) LocalDate birthday,
        @Schema(description = "Email; null for accounts registered by phone", example = "alice@example.com", nullable = true) String email,
        @Schema(description = "Phone number (E.164); null for accounts registered by email or OAuth", example = "+886912345678", nullable = true) String phoneNumber,
        @Schema(description = "How the account signs in: email_otp, phone_otp, google, line or apple", example = "email_otp", nullable = true) String authMethod,
        @Schema(description = "Roles: user, moderator, admin", example = "[\"user\"]") List<String> roles,
        @Schema(description = "False when suspended", example = "true") boolean active,
        @Schema(description = "Last sign-in; null if never", example = "2026-10-16T21:04:11+08:00", nullable = true) OffsetDateTime lastLoginAt,
        @Schema(description = "When the account was created", example = "2026-09-01T10:00:00+08:00") OffsetDateTime createdAt
) {
}
