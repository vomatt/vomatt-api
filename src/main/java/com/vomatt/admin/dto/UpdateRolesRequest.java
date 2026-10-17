package com.vomatt.admin.dto;

import java.util.List;

import com.vomatt.common.constant.UserRole;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** toString() is what the audit log stores in details. */
@Schema(description = "The account's complete new role set")
public record UpdateRolesRequest(
        @NotEmpty
        @ArraySchema(arraySchema = @Schema(description = "Roles replacing the current ones; duplicates are ignored", example = "[\"user\", \"moderator\"]"),
                schema = @Schema(allowableValues = {"user", "moderator", "admin"}))
        List<@NotNull @Pattern(regexp = UserRole.PATTERN) String> roles
) {
}
