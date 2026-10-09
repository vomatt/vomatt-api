package com.vomatt.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Google login request; the server validates {@code idToken} with Google.
 */
@Schema(description = "Google login request")
public record GoogleAuthRequest(

        @Schema(description = "ID token (JWT) issued by Google",
                requiredMode = Schema.RequiredMode.REQUIRED,
                example = "eyJhbGciOiJSUzI1NiIsImtpZCI6Ij...")
        @NotBlank String idToken
) {
}
