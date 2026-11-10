package com.vomatt.users.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "Community account profile update; omitted or null fields are left unchanged. The username cannot be changed")
public record UpdateCommunityRequest(
        @Schema(description = "Group name, shown as the display name (max 100 characters, not blank)",
                example = "Sunrise Tower Committee", maxLength = 100, nullable = true)
        @Size(max = 100) @Pattern(regexp = "(?s).*\\S.*", message = "must not be blank")
        String displayName,
        @Schema(description = "Bio (max 500 characters); an empty string clears it", example = "Residents' committee of Sunrise Tower",
                maxLength = 500, nullable = true)
        @Size(max = 500)
        String bio
) {}
