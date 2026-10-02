package com.vomatt.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * 檢查 email 是否已註冊的請求；用於前端決定要走「登入」還是「註冊」流程。
 */
@Schema(description = "檢查 email 是否已註冊的請求")
public record CheckEmailRequest(

        @Schema(description = "要檢查的電子郵件", requiredMode = Schema.RequiredMode.REQUIRED,
                example = "user@example.com")
        @NotBlank @Email String email
) {
}
