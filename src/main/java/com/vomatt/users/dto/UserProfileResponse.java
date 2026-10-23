package com.vomatt.users.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

@Schema(description = "Public view of a user; optional fields are null when the owner has not made them visible")
public record UserProfileResponse(
        @Schema(description = "Unique username", example = "alice")
        String username,
        @Schema(description = "Display name; null if the owner hid it (public by default) or never set one", example = "Alice", nullable = true)
        String displayName,
        @Schema(description = "Bio; null if the owner hid it (public by default) or never set one", example = "Poll enthusiast", nullable = true)
        String bio,
        @Schema(description = "Avatar as `bot:<kind>` (the bot cast); always public. Older accounts may hold an image URL; null if never set",
                example = "bot:boba", nullable = true)
        String avatarUrl,
        @Schema(description = "When the account was created", example = "2026-08-01T10:00:00+08:00")
        OffsetDateTime joinedAt,
        @Schema(description = "Number of Polls the user created that have opened (Open and Ended; Scheduled and Cancelled-before-opening excluded)", example = "4")
        int totalPolls,
        @Schema(description = "Number of Polls the user holds a Ballot in", example = "17")
        int totalVotes,
        // controllable fields: null when hidden. Email and birthday are never part of the public profile
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
