package com.vomatt.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * 寄送手機 OTP 簡訊的請求。
 *
 * <p>{@code type} 預設為 {@code login}（登入用）；註冊流程使用 {@code signup}（後端會在驗證時建立新帳號）。</p>
 */
@Schema(description = "發送手機 OTP 簡訊的請求")
public record PhoneSendOtpRequest(

        @Schema(description = "手機號碼", requiredMode = Schema.RequiredMode.REQUIRED,
                example = "0912345678")
        @NotBlank String phone,

        @Schema(description = "OTP 用途；預設 login。signup 用於註冊流程",
                example = "login", allowableValues = {"login", "signup"}, nullable = true)
        String type
) {
}
