package com.vomatt.common.redis;

import tools.jackson.core.type.TypeReference;

import java.time.Duration;
import java.util.Map;

/**
 * Redis 操作介面。
 *
 * 錯誤處理策略：
 * - 讀取 + 冪等寫入（get, hGet, hGetAll, hasKey, set, hSet, expire）：swallow-and-log，cache-aside 可 fail-soft。
 * - 正確性關鍵（setIfAbsent, incrementAndExpire, delete, hDelete）：丟 RedisOperationException。
 */
public interface RedisService {

    // ── 基本 String value ────────────────────────────────────────────

    void set(String cacheName, String key, Object value, Duration timeout);

    /**
     * 與 {@link #set} 相同，但寫入失敗時丟 RedisOperationException（不 swallow）。
     * 用於正確性關鍵寫入——OTP、refresh token reuse 偵測（CONSUMED）、token 本體等，
     * 靜默失敗會讓使用者拿到查無的 OTP 或讓安全偵測失效。
     */
    void setOrThrow(String cacheName, String key, Object value, Duration timeout);

    <T> T get(String cacheName, String key, Class<T> clazz);

    <T> T get(String cacheName, String key, TypeReference<T> typeRef);

    /** 刪除成功回傳 true；Redis 失敗丟 RedisOperationException。 */
    boolean delete(String cacheName, String key);

    /**
     * Atomic GETDEL：取出 key 的值並同時刪除。對應 Redis 6.2+ 的 GETDEL 命令。
     * 用於需要 atomic 「取出 + 刪除」的正確性關鍵情境（例：refresh token rotation，
     * 避免雙 instance 同時拿到同一份 token data）。
     * key 不存在回傳 null；Redis 失敗丟 RedisOperationException。
     */
    <T> T getAndDelete(String cacheName, String key, Class<T> clazz);

    /**
     * 透過 SCAN 比對 prefix 後批次刪除所有符合的 key（含 cacheName 前綴）。
     * 回傳實際刪除的數量；找不到回傳 0。Redis 失敗丟 RedisOperationException。
     */
    long deleteByPrefix(String cacheName, String keyPrefix);

    /** SET NX EX — 成功取得鎖回傳 true，key 已存在回傳 false，Redis 失敗丟 RedisOperationException。 */
    boolean setIfAbsent(String cacheName, String key, Object value, Duration timeout);

    // ── Hash ─────────────────────────────────────────────────────────

    /** 寫入 hash field；key 不存在時設定 TTL，已存在則不更動 TTL。 */
    void hSet(String cacheName, String key, String field, Object value, Duration ttl);

    /**
     * 與 {@link #hSet} 相同，但寫入失敗時丟 RedisOperationException（不 swallow）。
     * 用於正確性關鍵的索引寫入——refresh token 的 user→token 索引，靜默失敗會讓
     * revokeAllForUser 撤銷不完整、被竊 session 殘存。
     */
    void hSetOrThrow(String cacheName, String key, String field, Object value, Duration ttl);

    <T> T hGet(String cacheName, String key, String field, Class<T> clazz);

    /** 刪除 hash field；Redis 失敗丟 RedisOperationException。 */
    boolean hDelete(String cacheName, String key, String field);

    Map<String, Object> hGetAll(String cacheName, String key);

    // ── Counter ───────────────────────────────────────────────────────

    /**
     * INCR + 首次設定 TTL（counter == 1 時才 EXPIRE）。
     * Redis 失敗丟 RedisOperationException。
     */
    long incrementAndExpire(String cacheName, String key, Duration ttl);

    // ── TTL / existence ───────────────────────────────────────────────

    boolean expire(String cacheName, String key, Duration ttl);

    boolean hasKey(String cacheName, String key);

    // ── 交易（MULTI/EXEC）─────────────────────────────────────────────

    /**
     * P1-#17：在單一 MULTI/EXEC 交易內原子完成多筆正確性關鍵寫入，將數次 round-trip 收斂為一次。
     *
     * <p>整批原子套用（不會部分生效），連線層失敗則整批拋 {@link RedisOperationException}（fail-fast），
     * 語意等同逐筆 {@code setOrThrow}/{@code hSetOrThrow} 但更強（原子）。fail-soft 的寫入（如 grace cache）
     * 不應放入本交易，須由呼叫端另以 {@link #set} 寫出。</p>
     */
    void execInTransaction(java.util.List<RedisWrite> writes);

    /** {@link #execInTransaction} 的單筆寫入描述。 */
    record RedisWrite(Op op, String cacheName, String key, String field, Object value, Duration ttl) {
        public enum Op { SET, HPUT, EXPIRE, HDELETE }

        public static RedisWrite set(String cacheName, String key, Object value, Duration ttl) {
            return new RedisWrite(Op.SET, cacheName, key, null, value, ttl);
        }

        public static RedisWrite hput(String cacheName, String key, String field, Object value) {
            return new RedisWrite(Op.HPUT, cacheName, key, field, value, null);
        }

        public static RedisWrite expire(String cacheName, String key, Duration ttl) {
            return new RedisWrite(Op.EXPIRE, cacheName, key, null, null, ttl);
        }

        public static RedisWrite hdelete(String cacheName, String key, String field) {
            return new RedisWrite(Op.HDELETE, cacheName, key, field, null, null);
        }
    }
}
