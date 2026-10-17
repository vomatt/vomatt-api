package com.vomatt.admin.dto;

import java.util.List;
import java.util.TreeSet;

import com.vomatt.common.audit.AuditDetails;

import com.vomatt.common.constant.UserRole;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** The audit log stores the new roles in details, sorted and comma-separated (e.g. "admin,user"). */
@Schema(description = "The account's complete new role set")
public record UpdateRolesRequest(
        @NotEmpty
        @ArraySchema(arraySchema = @Schema(description = "Roles replacing the current ones; duplicates are ignored", example = "[\"user\", \"moderator\"]"),
                schema = @Schema(allowableValues = {"user", "moderator", "admin"}))
        List<@NotNull @Pattern(regexp = UserRole.PATTERN) String> roles
) implements AuditDetails {

    @Override
    public String auditDetails() {
        return String.join(",", new TreeSet<>(roles));
    }
}
