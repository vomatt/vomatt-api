package com.vomatt.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Request carrying a refresh token, used to refresh the access token or to log out.
 */
@Schema(description = "Request carrying a refresh token (refresh / logout)")
public record RefreshRequest(

        @Schema(description = "Refresh token from the latest login or refresh response; each refresh invalidates it and issues a new one",
                requiredMode = Schema.RequiredMode.REQUIRED,
                example = "8c7e3f4a-1234-5678-90ab-cdef12345678")
        @NotBlank String refreshToken
) {
}
