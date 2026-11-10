package com.vomatt.users.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

@Schema(description = "The member who receives the caller's OWNER role")
public record CommunityOwnerRequest(
        @Schema(description = "A current member of the community account", example = "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d47")
        @NotNull UUID userId
) {}
