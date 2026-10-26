package com.vomatt.users.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

@Schema(description = "Public view of a user; optional fields are null when the owner has not made them visible")
public record UserProfileResponse(
        @Schema(description = "User id", example = "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d47")
        String id,
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
        @Schema(description = "Number of users following this user; suspended accounts are not counted", example = "12")
        int followersCount,
        @Schema(description = "Number of users this user follows; suspended accounts are not counted", example = "8")
        int followingCount,
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
        String membershipLevel,
        // viewer relation: null for a signed-out caller, false on the caller's own profile
        @Schema(description = "Whether the caller follows this user; null when signed out, false on the caller's own profile",
                example = "false", nullable = true)
        Boolean isFollowing,
        @Schema(description = "Whether this user follows the caller; null when signed out, false on the caller's own profile",
                example = "true", nullable = true)
        Boolean followsYou,
        @Schema(description = "Whether the caller has blocked this user; null when signed out, false on the caller's own profile",
                example = "false", nullable = true)
        Boolean isBlocked
) {}
