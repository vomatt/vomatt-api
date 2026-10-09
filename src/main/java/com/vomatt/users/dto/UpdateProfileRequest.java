package com.vomatt.users.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "Profile update; omitted or null fields are left unchanged")
public class UpdateProfileRequest {

    @Schema(description = "New display name (max 100 characters); null or omitted keeps the current value",
            example = "Alice C.", maxLength = 100, nullable = true)
    @Size(max = 100, message = "Display name cannot exceed 100 characters")
    private String displayName;

    @Schema(description = "New bio (max 500 characters); null or omitted keeps the current value",
            example = "Poll enthusiast", maxLength = 500, nullable = true)
    @Size(max = 500, message = "Bio cannot exceed 500 characters")
    private String bio;
}
