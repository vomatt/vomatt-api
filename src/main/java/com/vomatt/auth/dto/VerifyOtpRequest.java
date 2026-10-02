package com.vomatt.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * 驗證 email OTP 並完成登入/註冊的請求。
 */
@Schema(description = "驗證 email OTP 的請求")
public record VerifyOtpRequest(

        @Schema(description = "電子郵件", example = "user@example.com")
        @Email String email,

        @Schema(description = "信件收到的 OTP 驗證碼", requiredMode = Schema.RequiredMode.REQUIRED,
                example = "123456")
        @NotBlank String code,

        @Schema(description = "是否限制管理員才可登入；填 \"true\" 僅 admin 帳號驗證通過",
                example = "false", allowableValues = {"true", "false"}, nullable = true)
        String checkRole
) {
}
