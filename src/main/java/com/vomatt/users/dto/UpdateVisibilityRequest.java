package com.vomatt.users.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * Update field visibility request
 * key is the field name (email, firstName, lastName, location, points, displayName, bio, membershipLevel),
 * value is true (visible) or false (hidden)
 */
@Schema(description = "Field visibility update for the public profile")
public record UpdateVisibilityRequest(
        @Schema(description = "Field name to visible flag. Allowed keys: email, firstName, lastName, location, points, displayName, bio, membershipLevel. "
                + "Omitted fields keep their current setting; unknown keys are ignored",
                example = "{\"email\": false, \"firstName\": true, \"location\": false}")
        @NotNull Map<String, Boolean> visibility
) {}
