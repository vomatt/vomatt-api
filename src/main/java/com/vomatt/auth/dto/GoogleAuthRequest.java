package com.vomatt.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Google 登入請求；後端會以 Google JWKS 驗證 idToken。
 */
@Schema(description = "Google 登入請求")
public record GoogleAuthRequest(

        @Schema(description = "Google 簽發的 ID token (JWT)",
                requiredMode = Schema.RequiredMode.REQUIRED,
                example = "eyJhbGciOiJSUzI1NiIsImtpZCI6Ij...")
        @NotBlank String idToken
) {
}
