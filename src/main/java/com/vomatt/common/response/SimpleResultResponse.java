package com.vomatt.common.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "通用動作回應；用於不需特定欄位的成功回應或簡單的 id/status 回傳")
public record SimpleResultResponse(
        @Schema(description = "操作是否成功", example = "true") boolean success,
        @Schema(description = "受影響資源 ID（可為 null）") UUID id,
        @Schema(description = "受影響資源狀態（可為 null）", example = "approved") String status,
        @Schema(description = "附加訊息（可為 null）") String message
) {

    public static SimpleResultResponse ok() {
        return new SimpleResultResponse(true, null, null, null);
    }

    public static SimpleResultResponse ok(UUID id) {
        return new SimpleResultResponse(true, id, null, null);
    }

    public static SimpleResultResponse ok(UUID id, String status) {
        return new SimpleResultResponse(true, id, status, null);
    }

    public static SimpleResultResponse ok(String message) {
        return new SimpleResultResponse(true, null, null, message);
    }

    public static SimpleResultResponse of(UUID id, String status, String message) {
        return new SimpleResultResponse(true, id, status, message);
    }
}
