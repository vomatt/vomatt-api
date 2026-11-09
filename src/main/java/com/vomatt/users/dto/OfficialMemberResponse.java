package com.vomatt.users.dto;

import com.vomatt.entity.OfficialRole;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

@Schema(description = "A member of an official account")
public record OfficialMemberResponse(
        @Schema(description = "Member's user id", example = "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d47") String userId,
        @Schema(description = "Member's username", example = "alice") String username,
        @Schema(description = "Member's display name; null if never set", example = "Alice", nullable = true) String displayName,
        @Schema(description = "Member's avatar as `bot:<kind>` or `/api/avatars/<id>.jpg`; null if never set", example = "bot:boba", nullable = true) String avatarUrl,
        @Schema(description = "Role: OWNER, ADMIN, CHIEF_EDITOR, EDITOR or ANALYST", example = "OWNER") OfficialRole role,
        @Schema(description = "When the user became a member", example = "2026-11-09T10:00:00+08:00") OffsetDateTime joinedAt
) {}
