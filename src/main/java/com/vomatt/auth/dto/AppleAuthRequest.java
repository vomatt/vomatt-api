package com.vomatt.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Apple Sign-In 登入請求；後端會以 Apple JWKS 驗證 idToken。
 */
@Schema(description = "Apple Sign-In 登入請求")
public record AppleAuthRequest(

        @Schema(description = "Apple 簽發的 identity token (JWT)",
                requiredMode = Schema.RequiredMode.REQUIRED,
                example = "eyJraWQiOiJZdXl1eVoxIiwiYWxnIjoiUlMyNTYifQ...")
        @NotBlank String idToken,

        @Schema(description = "首次登入時 Apple 提供的姓名（之後不會再帶）",
                example = "王小明", nullable = true)
        String fullName
) {
}
