package com.vomatt.common.exception;

import com.vomatt.common.i18n.MessageKey;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final MessageKey messageKey;
    private final Object[] messageArgs;

    /**
     * Optional structured error payload.
     *
     * <p>When {@link GlobalExceptionHandler} catches an {@link ApiException} and this field is non-null,
     * it is put into the response's {@code ApiResponse.data} ({@code success} stays false;
     * semantically it is "extra information attached to an error response", not success data).</p>
     *
     * <p>Backward compatible: the existing constructors/static factories that never carry data ({@link #badRequest}, {@link #notFound}, etc.)
     * all set this field to null, behaving exactly as before; only exceptions created via {@link #withData} carry data.</p>
     */
    private final Object data;

    // === New usage (i18n, recommended) ===

    public ApiException(HttpStatus status, MessageKey key, Object... args) {
        this(status, key, null, args);
    }

    /**
     * Internal constructor that can carry structured {@code data}.
     *
     * <p>The last parameter is deliberately declared as {@code Object[]} (not {@code Object...}) to avoid
     * overload ambiguity with {@link #ApiException(HttpStatus, MessageKey, Object...)} above. If both third parameters were
     * {@code Object} varargs, the compiler could not tell whether a single object passed by the caller is
     * {@code data} or the first element of {@code args}, and compilation would fail. So this constructor stays {@code private},
     * called only by the {@link #withData} static factory in this class.</p>
     */
    private ApiException(HttpStatus status, MessageKey key, Object data, Object[] args) {
        super(key.code());
        this.status = status;
        this.messageKey = key;
        this.messageArgs = args;
        this.data = data;
    }

    public static ApiException badRequest(MessageKey key, Object... args) {
        return new ApiException(HttpStatus.BAD_REQUEST, key, args);
    }

    public static ApiException unauthorized(MessageKey key, Object... args) {
        return new ApiException(HttpStatus.UNAUTHORIZED, key, args);
    }

    public static ApiException notFound(MessageKey key, Object... args) {
        return new ApiException(HttpStatus.NOT_FOUND, key, args);
    }

    public static ApiException forbidden(MessageKey key, Object... args) {
        return new ApiException(HttpStatus.FORBIDDEN, key, args);
    }

    public static ApiException conflict(MessageKey key, Object... args) {
        return new ApiException(HttpStatus.CONFLICT, key, args);
    }

    /** 429 Too Many Requests: service-layer quota (e.g. the daily invitation or Ballot limit). */
    public static ApiException tooManyRequests(MessageKey key, Object... args) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, key, args);
    }

    /**
     * Creates an exception carrying structured {@code data}; usable with any status code (not just 422).
     *
     * <p>Example: {@code ApiException.withData(HttpStatus.UNPROCESSABLE_ENTITY, MessageKey.VOTE_NOT_ALLOWED, extraData)}
     * makes {@link GlobalExceptionHandler} respond with
     * {@code { "success": false, "error": "...", "data": { ... } } }.</p>
     *
     * @param data structured payload put into the error response's {@code ApiResponse.data}; null behaves like a plain error (no data)
     */
    public static ApiException withData(HttpStatus status, MessageKey key, Object data, Object... args) {
        return new ApiException(status, key, data, args);
    }
}
