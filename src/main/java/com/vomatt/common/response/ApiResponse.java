package com.vomatt.common.response;

public record ApiResponse<T>(
        boolean success,
        T data,
        String message,
        String errorCode,
        String error
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
