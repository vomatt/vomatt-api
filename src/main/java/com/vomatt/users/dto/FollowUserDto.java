package com.vomatt.users.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Follower or following list item: public profile fields, masked by the owner's visibility settings, "
        + "plus the caller's relation to the user")
public record FollowUserDto(
        @Schema(description = "User id (UUIDv7)", example = "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d47")
        String id,
        @Schema(description = "Unique username", example = "alice")
        String username,
        @Schema(description = "Display name; null if the owner hid it (public by default) or never set one", example = "Alice", nullable = true)
        String displayName,
        @Schema(description = "Avatar as `bot:<kind>` (the bot cast). Older accounts may hold an image URL; null if never set",
                example = "bot:boba", nullable = true)
        String avatarUrl,
        @Schema(description = "Whether the caller follows this user; false for the caller's own entry", example = "true")
        boolean isFollowing,
        @Schema(description = "Whether this user follows the caller; false for the caller's own entry", example = "false")
        boolean followsYou
) {}
