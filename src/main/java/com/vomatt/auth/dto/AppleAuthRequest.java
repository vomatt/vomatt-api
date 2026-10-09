package com.vomatt.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Sign in with Apple request; the server verifies {@code idToken} against Apple's JWKS.
 */
@Schema(description = "Sign in with Apple request")
public record AppleAuthRequest(

        @Schema(description = "Identity token (JWT) issued by Apple",
                requiredMode = Schema.RequiredMode.REQUIRED,
                example = "eyJraWQiOiJZdXl1eVoxIiwiYWxnIjoiUlMyNTYifQ...")
        @NotBlank String idToken,

        @Schema(description = "Full name, used only when a new user is created. Apple provides the name on the first authorization only, so send it then; null or ignored afterwards",
                example = "Ming Wang", nullable = true)
        String fullName
) {
}
