package com.vomatt.users.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Profile response — return all fields when authenticated user queries their own profile
 */
@Schema(description = "The caller's own profile; every field is returned regardless of visibility settings")
public record MyProfileResponse(
        @Schema(description = "User id (UUIDv7)", example = "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d47")
        String id,
        @Schema(description = "Unique username", example = "alice")
        String username,
        @Schema(description = "Email; null if the account signed up with phone only", example = "alice@example.com", nullable = true)
        String email,
        @Schema(description = "Phone number; null if the account signed up with email only", example = "+886912345678", nullable = true)
        String phoneNumber,
        @Schema(description = "First name; null if not set", example = "Alice", nullable = true)
        String firstName,
        @Schema(description = "Last name; null if not set", example = "Chen", nullable = true)
        String lastName,
        @Schema(description = "Display name; null if not set", example = "Alice", nullable = true)
        String displayName,
        @Schema(description = "Bio; null if not set", example = "Poll enthusiast", nullable = true)
        String bio,
        @Schema(description = "Location; null if not set", example = "Taipei", nullable = true)
        String location,
        @Schema(description = "Points", example = "120")
        int points,
        @Schema(description = "Membership level; null if not set", example = "basic", nullable = true)
        String membershipLevel,
        @Schema(description = "False when the account is suspended", example = "true")
        boolean active,
        @Schema(description = "Last sign-in time; null if the user never signed in", example = "2026-10-09T09:15:00+08:00", nullable = true)
        OffsetDateTime lastLoginAt,
        @Schema(description = "When the account was created", example = "2026-08-01T10:00:00+08:00")
        OffsetDateTime joinedAt,
        @Schema(description = "Number of Polls the user created", example = "4")
        int totalPolls,
        @Schema(description = "Number of Polls the user holds a Ballot in", example = "17")
        int totalVotes,
        @Schema(description = "Field name to visible flag, i.e. what other users see on the public profile; "
                + "keys: email, firstName, lastName, location, points, displayName, bio, membershipLevel",
                example = "{\"email\": false, \"displayName\": true, \"bio\": true}")
        Map<String, Boolean> visibilitySettings
) {}
