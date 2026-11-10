package com.vomatt.users.dto;

import com.vomatt.entity.CommunityRole;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

@Schema(description = "A user to add to a community account, with a role")
public record CommunityMemberRequest(
        @Schema(description = "The active user to add", example = "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d47")
        @NotNull UUID userId,
        @Schema(description = "Role: OWNER, ADMIN, CHIEF_EDITOR, EDITOR or ANALYST; only an OWNER can grant OWNER", example = "EDITOR")
        @NotNull CommunityRole role
) {}
