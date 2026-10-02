package com.vomatt.common.exception;

import com.vomatt.common.i18n.LocalizedMessageService;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.response.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final LocalizedMessageService messageService;

    /**
     * 處理業務例外（{@link ApiException}）。
     *
     * <p>回應型別為 {@code ApiResponse<Object>}（而非既有的 {@code ApiResponse<Void>}）——
     * 這是承載 {@link ApiException#getData()} 的侵入性最小做法：{@link ApiResponse} record
     * 本身不改動（其他 handler 與既有呼叫端完全不受影響），僅在此處依 data 是否存在，
     * 直接組出對應的 {@code ApiResponse} 實例。無 data 時走既有 {@link ApiResponse#error} 靜態工廠
     * （行為與擴充前完全一致，data 恆為 null）；有 data 時才用 record 的正典建構子附上結構化資料。</p>
     */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Object>> handleApiException(ApiException ex) {
        String message = ex.getMessageKey() != null
                ? messageService.resolve(ex.getMessageKey(), ex.getMessageArgs())
                : ex.getMessage();
        String errorCode = ex.getMessageKey() != null ? ex.getMessageKey().code() : null;
        ApiResponse<Object> body = ex.getData() != null
                ? new ApiResponse<>(false, ex.getData(), message, errorCode, message)
                : ApiResponse.error(message, errorCode);
        return ResponseEntity.status(ex.getStatus()).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream().findFirst()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .orElse(messageService.resolve(MessageKey.COMMON_VALIDATION_FAILED));
        return ResponseEntity.badRequest().body(ApiResponse.error(message, MessageKey.COMMON_VALIDATION_FAILED.code()));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream().findFirst()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .orElse(messageService.resolve(MessageKey.COMMON_VALIDATION_FAILED));
        return ResponseEntity.badRequest().body(ApiResponse.error(message, MessageKey.COMMON_VALIDATION_FAILED.code()));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParam(MissingServletRequestParameterException ex) {
        String message = messageService.resolve(MessageKey.COMMON_MISSING_PARAM, ex.getParameterName());
        return ResponseEntity.badRequest().body(ApiResponse.error(message, MessageKey.COMMON_MISSING_PARAM.code()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String message = messageService.resolve(MessageKey.COMMON_TYPE_MISMATCH, ex.getName());
        return ResponseEntity.badRequest().body(ApiResponse.error(message, MessageKey.COMMON_TYPE_MISMATCH.code()));
    }

    /** 非法輸入（例：路徑中的 UUID 格式錯誤、無法產生 slug 的標籤名）視為 400，而非被兜底成 500。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException ex) {
        log.debug("Illegal argument: {}", ex.getMessage());
        String message = messageService.resolve(MessageKey.COMMON_BAD_REQUEST);
        return ResponseEntity.badRequest().body(ApiResponse.error(message, MessageKey.COMMON_BAD_REQUEST.code()));
    }

    /**
     * 處理授權失敗（403）。
     * Spring Security 6 在 method-security（{@code @PreAuthorize}）失敗時，
     * 會於 DispatcherServlet 內丟出 {@link org.springframework.security.authorization.AuthorizationDeniedException}，
     * 它是 {@link AccessDeniedException} 的子類；filter-chain 來源亦同。
     * 單一 handler 即可涵蓋兩種來源，避免被 {@code Exception.class} 兜底handler 誤判為 500。
     * 授權失敗屬預期情況，僅以 WARN 記錄訊息（不印 stacktrace），避免日誌噪音。
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
        log.warn("Access denied: {}", ex.getMessage());
        String message = messageService.resolve(MessageKey.COMMON_FORBIDDEN);
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error(message, MessageKey.COMMON_FORBIDDEN.code()));
    }

    /**
     * 處理 Redis 正確性關鍵寫入/讀取失敗（503）。
     * OTP 寫入、refresh token reuse 偵測等改為拋出型後，Redis 故障會冒泡到此；
     * 回 503（可重試）而非被 {@code Exception.class} 兜底成 500，並以 WARN 記錄（非預期但屬基礎設施抖動）。
     */
    @ExceptionHandler(com.vomatt.common.redis.RedisOperationException.class)
    public ResponseEntity<ApiResponse<Void>> handleRedisFailure(com.vomatt.common.redis.RedisOperationException ex) {
        log.warn("Redis operation failed: {}", ex.getMessage());
        String message = messageService.resolve(MessageKey.COMMON_SERVICE_UNAVAILABLE);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.error(message, MessageKey.COMMON_SERVICE_UNAVAILABLE.code()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneric(Exception ex) {
        log.error("Unhandled exception", ex);
        String message = messageService.resolve(MessageKey.COMMON_INTERNAL_ERROR);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(message, MessageKey.COMMON_INTERNAL_ERROR.code()));
    }
}
