package com.vomatt.users.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

@Schema(description = "Public view of a user; optional fields are null when the owner has not made them visible")
public record UserProfileResponse(
        @Schema(description = "Unique username", example = "alice")
        String username,
        @Schema(description = "Display name; public by default, null if the user never set one", example = "Alice", nullable = true)
        String displayName,
        @Schema(description = "Bio; public by default, null if the user never set one", example = "Poll enthusiast", nullable = true)
        String bio,
        @Schema(description = "When the account was created", example = "2026-08-01T10:00:00+08:00")
        OffsetDateTime joinedAt,
        @Schema(description = "Number of Polls the user created", example = "4")
        int totalPolls,
        @Schema(description = "Number of Polls the user holds a Ballot in", example = "17")
        int totalVotes,
        // controllable fields: null when hidden
        @Schema(description = "Email; null when the owner keeps it hidden (default) or has none", example = "alice@example.com", nullable = true)
        String email,
        @Schema(description = "First name; null when hidden (default) or not set", example = "Alice", nullable = true)
        String firstName,
        @Schema(description = "Last name; null when hidden (default) or not set", example = "Chen", nullable = true)
        String lastName,
        @Schema(description = "Location; null when hidden (default) or not set", example = "Taipei", nullable = true)
        String location,
        @Schema(description = "Points; null when hidden (default)", example = "120", nullable = true)
        Integer points,
        @Schema(description = "Membership level; null when hidden (default) or not set", example = "basic", nullable = true)
        String membershipLevel
) {}
