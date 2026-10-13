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
        @Schema(description = "Avatar URL; null until the user or an OAuth provider (Google / LINE) supplies one",
                example = "https://cdn.example.com/avatar/abc.jpg", nullable = true)
        String avatarUrl
) {}
