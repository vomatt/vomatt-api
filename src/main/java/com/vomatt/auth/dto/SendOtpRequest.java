package com.vomatt.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;

/**
 * 發送 email OTP 的請求。
 *
 * <p>{@code checkRole} 為字串布林（"true" / "false"）；若為 "true" 則僅允許 admin 角色取得 OTP（用於後台登入）。</p>
 */
@Schema(description = "發送 email OTP 的請求")
public record SendOtpRequest(

        @Schema(description = "電子郵件", example = "user@example.com")
        @Email String email,

        @Schema(description = "是否限制管理員才可登入；填 \"true\" 只允許 admin 取得 OTP",
                example = "false", allowableValues = {"true", "false"}, nullable = true)
        String checkRole
) {
}
