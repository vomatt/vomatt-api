package com.vomatt.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * 用 refresh token 換取新 access token 的請求。
 */
@Schema(description = "刷新 access token 的請求")
public record RefreshRequest(

        @Schema(description = "登入時取得的 refresh token",
                requiredMode = Schema.RequiredMode.REQUIRED,
                example = "8c7e3f4a-1234-5678-90ab-cdef12345678")
        @NotBlank String refreshToken
) {
}
