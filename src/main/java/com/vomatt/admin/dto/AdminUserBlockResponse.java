package com.vomatt.admin.dto;

import java.time.OffsetDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "The other side of a block relationship, as shown in the back office")
public record AdminUserBlockResponse(
        @Schema(description = "User ID (UUIDv7) of the other user", example = "0199c1a2-8c4f-7e5b-a021-3d6f9b2c4e58") String userId,
        @Schema(description = "Username", example = "bob") String username,
        @Schema(description = "Display name; null when unset", example = "Bob", nullable = true) String displayName,
        @Schema(description = "False when suspended", example = "true") boolean active,
        @Schema(description = "When the block was made", example = "2026-10-20T09:15:00+08:00") OffsetDateTime blockedAt
) {
}
