package com.vomatt.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * LINE Login request; the server exchanges {@code code} + {@code redirectUri} with LINE for an access token / id token.
 */
@Schema(description = "LINE login request")
public record LineAuthRequest(

        @Schema(description = "Authorization code returned by LINE after the user authorizes",
                requiredMode = Schema.RequiredMode.REQUIRED,
                example = "abc123def456")
        @NotBlank String code,

        @Schema(description = "Redirect URI used in the LINE authorization request; must match the one registered with LINE",
                requiredMode = Schema.RequiredMode.REQUIRED,
                example = "https://app.example.com/auth/line/callback")
        @NotBlank String redirectUri
) {
}
