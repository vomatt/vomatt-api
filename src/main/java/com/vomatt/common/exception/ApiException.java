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
     * 可選的結構化錯誤 payload（例：{@code {distanceM: 384}}）。
     *
     * <p>{@link GlobalExceptionHandler} 攔截 {@link ApiException} 時，若此欄位非 null，
     * 會將其塞進回應的 {@code ApiResponse.data}（{@code success} 欄位仍固定為 false，
     * 語意上是「錯誤回應附帶的額外資訊」而非成功資料）。</p>
     *
     * <p>向後相容：既有一律不帶 data 的建構子/靜態工廠（{@link #badRequest}、{@link #notFound} 等）
     * 皆將此欄位設為 null，行為與擴充前完全一致；僅透過 {@link #withData} 建立的例外才會攜帶資料。</p>
     */
    private final Object data;

    // === 新用法（i18n，推薦） ===

    public ApiException(HttpStatus status, MessageKey key, Object... args) {
        this(status, key, null, args);
    }

    /**
     * 內部用建構子：可攜帶結構化 {@code data}。
     *
     * <p>刻意將最後一個參數宣告為 {@code Object[]}（而非 {@code Object...}），避免與上方
     * {@link #ApiException(HttpStatus, MessageKey, Object...)} 產生多載歧義——若兩者的第三參數皆為
     * {@code Object} 型別的可變參數，呼叫端傳入單一物件時，編譯器無法判斷該物件應視為
     * {@code data} 還是 {@code args} 的第一個元素，會直接編譯失敗。因此本建構子維持 {@code private}，
     * 僅供類別內的 {@link #withData} 靜態工廠呼叫。</p>
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

    /** 422 Unprocessable Entity：語意上合法但業務規則無法處理（例：GPS 超出打卡範圍、庫存不足、餘額不足）。 */
    public static ApiException unprocessable(MessageKey key, Object... args) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, key, args);
    }

    /** 429 Too Many Requests：公開端點的 service 層配額限制（例：M11 guest booking 每日配額）。 */
    public static ApiException tooManyRequests(MessageKey key, Object... args) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, key, args);
    }

    /**
     * 建立攜帶結構化 {@code data} 的例外，供任意狀態碼使用（不限 422）。
     *
     * <p>例：{@code ApiException.withData(HttpStatus.UNPROCESSABLE_ENTITY, MessageKey.VOTE_NOT_ALLOWED, extraData)}
     * 會讓 {@link GlobalExceptionHandler} 回應
     * {@code { "success": false, "error": "...", "data": { ... } } }。</p>
     *
     * @param data 結構化 payload，塞進錯誤回應的 {@code ApiResponse.data}；null 等同一般錯誤（無 data）
     */
    public static ApiException withData(HttpStatus status, MessageKey key, Object data, Object... args) {
        return new ApiException(status, key, data, args);
    }
}
