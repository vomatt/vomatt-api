package com.vomatt.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * LINE Login 請求；後端會以 code + redirectUri 向 LINE 換取 access token / id token。
 */
@Schema(description = "LINE 登入請求")
public record LineAuthRequest(

        @Schema(description = "LINE OAuth 授權後拿到的 code",
                requiredMode = Schema.RequiredMode.REQUIRED,
                example = "abc123def456")
        @NotBlank String code,

        @Schema(description = "與 LINE 端設定一致的 redirect URI",
                requiredMode = Schema.RequiredMode.REQUIRED,
                example = "https://app.example.com/auth/line/callback")
        @NotBlank String redirectUri
) {
}
