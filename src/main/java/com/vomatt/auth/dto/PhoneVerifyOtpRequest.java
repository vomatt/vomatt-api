package com.vomatt.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * 驗證手機 OTP 並完成登入/註冊的請求。
 */
@Schema(description = "驗證手機 OTP 的請求")
public record PhoneVerifyOtpRequest(

        @Schema(description = "手機號碼", requiredMode = Schema.RequiredMode.REQUIRED,
                example = "0912345678")
        @NotBlank String phone,

        @Schema(description = "簡訊收到的 OTP 驗證碼", requiredMode = Schema.RequiredMode.REQUIRED,
                example = "123456")
        @NotBlank String code
) {
}
