package com.vomatt.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Email 註冊狀態檢查結果")
public record AuthEmailExistsResponse(

        @Schema(description = "Email 是否已註冊", example = "true")
        boolean exists
) {
}
