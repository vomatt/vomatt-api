package com.vomatt.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 登入或註冊成功後的回應；包含 access token、refresh token，以及使用者基本資訊。
 *
 * <p>Access token 預設有效 1 小時、refresh token 預設 30 天（可由環境變數調整）。</p>
 */
@Schema(description = "登入/註冊成功的回應")
public record AuthResponse(

        @Schema(description = "JWT access token（請放在 Authorization: Bearer 標頭）",
                example = "eyJhbGciOiJIUzI1NiJ9...")
        String token,

        @Schema(description = "刷新用 refresh token；access token 過期時用此換新",
                example = "8c7e3f4a-1234-5678-90ab-cdef12345678")
        String refreshToken,

        @Schema(description = "使用者基本資訊")
        UserInfo user
) {

    /**
     * 登入回應中的使用者基本資訊。
     */
    @Schema(description = "登入回應中的使用者基本資訊")
    public record UserInfo(

            @Schema(description = "使用者 ID（UUID v7）",
                    example = "0190a8b2-1234-7890-abcd-ef0123456789")
            String id,

            @Schema(description = "電子郵件；OAuth 登入若 provider 未提供 email 可能為 null",
                    example = "user@example.com", nullable = true)
            String email,

            @Schema(description = "使用者名稱（公開個人頁路徑用，註冊時自動產生）", example = "john_0427")
            String username,

            @Schema(description = "顯示名稱", example = "王小明", nullable = true)
            String displayName,

            @Schema(description = "使用者角色清單",
                    example = "[\"user\"]",
                    allowableValues = {"user", "moderator", "admin"})
            List<String> roles,

            @Schema(description = "頭像 URL", example = "https://cdn.example.com/avatar/abc.jpg",
                    nullable = true)
            String avatarUrl,

            @Schema(description = "手機號碼", example = "0912345678", nullable = true)
            String phoneNumber
    ) {
    }
}
