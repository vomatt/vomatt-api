package com.vomatt.users.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "Profile update; omitted or null fields are left unchanged")
public class UpdateProfileRequest {

    // "search" would be shadowed by GET /api/users/search; "me" is already excluded by the 3-character minimum
    @Schema(description = "New username, used in the public profile path: 3-50 characters of [a-z0-9_], not `search`; "
            + "null or omitted keeps the current value", example = "alice_c", minLength = 3, maxLength = 50, nullable = true)
    @Pattern(regexp = "^(?!search$)[a-z0-9_]{3,50}$",
            message = "Username must be 3-50 characters of lowercase letters, digits or underscore")
    private String username;

    @Schema(description = "New display name (max 100 characters); null or omitted keeps the current value",
            example = "Alice C.", maxLength = 100, nullable = true)
    @Size(max = 100, message = "Display name cannot exceed 100 characters")
    private String displayName;

    @Schema(description = "New bio (max 500 characters); null or omitted keeps the current value",
            example = "Poll enthusiast", maxLength = 500, nullable = true)
    @Size(max = 500, message = "Bio cannot exceed 500 characters")
    private String bio;
}
