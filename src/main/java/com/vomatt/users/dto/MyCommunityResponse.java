package com.vomatt.users.dto;

import com.vomatt.entity.CommunityRole;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A community account the caller belongs to, for the account switcher")
public record MyCommunityResponse(
        @Schema(description = "Community account's user id; send it as `X-Act-As` to act as the account", example = "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d48") String communityId,
        @Schema(description = "Community account's username", example = "sunrise_tower") String username,
        @Schema(description = "Group name", example = "Sunrise Tower Committee", nullable = true) String displayName,
        @Schema(description = "Logo as `bot:<kind>` or `/api/avatars/<id>.jpg`; null if never set", example = "/api/avatars/0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d49.jpg", nullable = true) String avatarUrl,
        @Schema(description = "The caller's role: OWNER, ADMIN, CHIEF_EDITOR, EDITOR or ANALYST", example = "EDITOR") CommunityRole role,
        @Schema(description = "Locked: readable, but writes while acting as it answer 403 `community.locked` (except deleting it, "
                + "transferring ownership and leaving); an OWNER with room unlocks it with `POST /api/communities/{communityId}/unlock`",
                example = "false") boolean locked
) {}
