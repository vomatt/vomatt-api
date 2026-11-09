package com.vomatt.users.dto;

import com.vomatt.common.constant.ReservedUsernames;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

@Schema(description = "A new official account and its first OWNER")
public record CreateOfficialRequest(
        @Schema(description = "Username of the official account: 3-50 characters of [a-z0-9_], not a reserved name",
                example = "sunrise_tower", minLength = 3, maxLength = 50)
        @NotNull
        @Pattern(regexp = ReservedUsernames.USERNAME_PATTERN,
                message = "Username must be 3-50 characters of lowercase letters, digits or underscore, and not a reserved name")
        String username,
        @Schema(description = "Group name, shown as the display name (max 100 characters)", example = "Sunrise Tower Committee", maxLength = 100)
        @NotBlank @Size(max = 100)
        String displayName,
        @Schema(description = "The active user who becomes the first OWNER", example = "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d47")
        @NotNull
        UUID ownerUserId
) {}
