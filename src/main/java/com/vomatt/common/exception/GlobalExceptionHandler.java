package com.vomatt.common.exception;

import com.vomatt.common.i18n.LocalizedMessageService;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.response.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import java.sql.SQLException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    /** PostgreSQL SQLSTATE for unique_violation. */
    private static final String UNIQUE_VIOLATION = "23505";

    private final LocalizedMessageService messageService;

    /**
     * Handles business exceptions ({@link ApiException}).
     *
     * <p>The response type is {@code ApiResponse<Object>} (not the existing {@code ApiResponse<Void>}):
     * this is the least invasive way to carry {@link ApiException#getData()}. The {@link ApiResponse} record
     * itself is unchanged (other handlers and existing callers are unaffected); only here, depending on whether data exists,
     * we build the matching {@code ApiResponse} instance. Without data it uses the existing {@link ApiResponse#error} static factory
     * (same behavior as before, data is always null); with data it uses the record's canonical constructor to attach it.</p>
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

    /** Illegal input (e.g. malformed UUID in the path, a tag name that cannot produce a slug) is a 400, not caught as a 500 by the fallback. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException ex) {
        log.debug("Illegal argument: {}", ex.getMessage());
        String message = messageService.resolve(MessageKey.COMMON_BAD_REQUEST);
        return ResponseEntity.badRequest().body(ApiResponse.error(message, MessageKey.COMMON_BAD_REQUEST.code()));
    }

    /**
     * Handles authorization failures (403).
     * When method security ({@code @PreAuthorize}) fails, Spring Security 6
     * throws {@link org.springframework.security.authorization.AuthorizationDeniedException} inside the DispatcherServlet,
     * a subclass of {@link AccessDeniedException}; the same applies to the filter chain.
     * One handler covers both sources, so they are not misreported as 500 by the {@code Exception.class} fallback handler.
     * Authorization failures are expected, so only the message is logged at WARN (no stack trace) to avoid log noise.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
        log.warn("Access denied: {}", ex.getMessage());
        return error(HttpStatus.FORBIDDEN, MessageKey.COMMON_FORBIDDEN);
    }

    /**
     * Handles failures of correctness-critical Redis writes/reads (503).
     * Since OTP writes, refresh token reuse detection, etc. now throw, Redis failures bubble up here;
     * return 503 (retryable) instead of the {@code Exception.class} fallback 500, logged at WARN (unexpected, but infrastructure flakiness).
     */
    @ExceptionHandler(com.vomatt.common.redis.RedisOperationException.class)
    public ResponseEntity<ApiResponse<Void>> handleRedisFailure(com.vomatt.common.redis.RedisOperationException ex) {
        log.warn("Redis operation failed: {}", ex.getMessage());
        return error(HttpStatus.SERVICE_UNAVAILABLE, MessageKey.COMMON_SERVICE_UNAVAILABLE);
    }

    /** Unknown paths are a 404, not caught as a 500 (with a stack trace) by the fallback. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException ex) {
        log.debug("No resource: {}", ex.getResourcePath());
        return error(HttpStatus.NOT_FOUND, MessageKey.COMMON_NOT_FOUND);
    }

    /** Malformed or unreadable request body (bad JSON, wrong field type) is a client error, not a 500. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotReadable(HttpMessageNotReadableException ex) {
        log.warn("Unreadable request body: {}", ex.getMessage());
        return error(HttpStatus.BAD_REQUEST, MessageKey.COMMON_BAD_REQUEST);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        log.warn("Method not supported: {}", ex.getMessage());
        return error(HttpStatus.METHOD_NOT_ALLOWED, MessageKey.COMMON_BAD_REQUEST);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex) {
        log.warn("Media type not supported: {}", ex.getMessage());
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, MessageKey.COMMON_BAD_REQUEST);
    }

    /**
     * A unique constraint the service did not pre-check (e.g. a concurrent duplicate insert) is a conflict.
     * Any other integrity violation (FK, NOT NULL, CHECK) is a bug and goes to the 500 fallback with its stack trace.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrity(DataIntegrityViolationException ex) {
        if (ex.getMostSpecificCause() instanceof SQLException sql && UNIQUE_VIOLATION.equals(sql.getSQLState())) {
            log.warn("Unique constraint violation: {}", sql.getMessage());
            return error(HttpStatus.CONFLICT, MessageKey.COMMON_CONFLICT);
        }
        return handleGeneric(ex);
    }

    /** A row lock not acquired within the transaction's lock_timeout: the resource is busy, so retrying is safe. */
    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleLockTimeout(PessimisticLockingFailureException ex) {
        log.warn("Lock not acquired: {}", ex.getMostSpecificCause().getMessage());
        return error(HttpStatus.CONFLICT, MessageKey.COMMON_CONFLICT);
    }

    private ResponseEntity<ApiResponse<Void>> error(HttpStatus status, MessageKey key) {
        return ResponseEntity.status(status).body(ApiResponse.error(messageService.resolve(key), key.code()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneric(Exception ex) {
        log.error("Unhandled exception", ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, MessageKey.COMMON_INTERNAL_ERROR);
    }
}
