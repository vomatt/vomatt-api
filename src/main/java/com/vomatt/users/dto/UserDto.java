package com.vomatt.users.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "User search result item; only public profile fields, masked by the owner's visibility settings")
public record UserDto(
        @Schema(description = "User id (UUIDv7)", example = "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d47")
        String id,
        @Schema(description = "Unique username", example = "alice")
        String username,
        @Schema(description = "Display name; null if the owner hid it (public by default) or never set one", example = "Alice", nullable = true)
        String displayName,
        @Schema(description = "First name; null if the owner hid it (hidden by default) or never set one", example = "Alice", nullable = true)
        String firstName,
        @Schema(description = "Last name; null if the owner hid it (hidden by default) or never set one", example = "Chen", nullable = true)
        String lastName,
        @Schema(description = "Avatar as `bot:<kind>` (the bot cast) or `/api/avatars/<id>.jpg` (an uploaded image, a path on the API origin). Older accounts may hold an image URL; null if never set",
                example = "bot:boba", nullable = true)
        String avatarUrl,
        @Schema(description = "Whether this is a community account (a group's page, shown with a badge)", example = "false")
        boolean community
) {}
