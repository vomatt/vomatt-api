package com.vomatt.users.dto;

import com.vomatt.entity.CommunityRole;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "A member's new role")
public record CommunityRoleRequest(
        @Schema(description = "Role: OWNER, ADMIN, CHIEF_EDITOR, EDITOR or ANALYST; only an OWNER can grant OWNER", example = "ADMIN")
        @NotNull CommunityRole role
) {}
