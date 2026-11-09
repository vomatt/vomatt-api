package com.vomatt.users.dto;

import com.vomatt.entity.OfficialRole;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "A member's new role")
public record OfficialRoleRequest(
        @Schema(description = "Role: OWNER, ADMIN, EDITOR or ANALYST; only an OWNER can grant OWNER", example = "ADMIN")
        @NotNull OfficialRole role
) {}
