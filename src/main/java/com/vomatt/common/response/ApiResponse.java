package com.vomatt.common.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Envelope of every API response, success or error")
public record ApiResponse<T>(
        @Schema(description = "true on success, false on error", example = "true") boolean success,
        @Schema(description = "Response payload; null on errors (a few business errors carry structured data) and on operations without a result", nullable = true) T data,
        @Schema(description = "Localized text following Accept-Language. On success it is usually null, except operations that attach a toast-ready text; on error it is the error message", example = "Operation completed", nullable = true) String message,
        @Schema(description = "Stable machine-readable error code (lowercase, dot-separated); branch on this, not on `message`. Null on success", example = "vote.not_found", nullable = true) String errorCode,
        @Schema(description = "Legacy duplicate of `message` on errors; null on success. Use `message` instead", example = "Poll not found", nullable = true) String error
) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null, null, null);
    }

    public static <T> ApiResponse<T> ok() {
        return new ApiResponse<>(true, null, null, null, null);
    }

    /**
     * 成功並附帶 i18n 訊息（例：「預約建立成功」）供前端 toast 顯示。
     */
    public static <T> ApiResponse<T> ok(T data, String message) {
        return new ApiResponse<>(true, data, message, null, null);
    }

    public static <T> ApiResponse<T> error(String message) {
        return new ApiResponse<>(false, null, message, null, message);
    }

    public static <T> ApiResponse<T> error(String message, String errorCode) {
        return new ApiResponse<>(false, null, message, errorCode, message);
    }
}
