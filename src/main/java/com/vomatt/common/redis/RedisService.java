package com.vomatt.common.redis;

import tools.jackson.core.type.TypeReference;

import java.time.Duration;
import java.util.Map;

/**
 * Redis operations interface.
 *
 * Error handling strategy:
 * - Reads + idempotent writes (get, hGet, hGetAll, hasKey, set, expire): swallow-and-log; cache-aside can fail soft.
 * - Correctness-critical (getOrThrow, getAndDelete, setOrThrow, setIfAbsent, incrementAndExpire, delete, hDelete,
 *   hasKeyOrThrow, execInTransaction, rotateRefreshToken): throw RedisOperationException.
 */
public interface RedisService {

    // ── Basic String value ───────────────────────────────────────────

    void set(String cacheName, String key, Object value, Duration timeout);

    /**
     * Same as {@link #set}, but throws RedisOperationException on write failure (no swallowing).
     * Used for correctness-critical writes (OTP, refresh token reuse detection (CONSUMED), the token itself, etc.),
     * where a silent failure would give the user an OTP that cannot be found or disable security detection.
     */
    void setOrThrow(String cacheName, String key, Object value, Duration timeout);

    <T> T get(String cacheName, String key, Class<T> clazz);

    <T> T get(String cacheName, String key, TypeReference<T> typeRef);

    /** Same as {@link #get(String, String, Class)}, but throws RedisOperationException on Redis failure, where a miss must be real. */
    <T> T getOrThrow(String cacheName, String key, Class<T> clazz);

    /** Returns true on successful delete; throws RedisOperationException on Redis failure. */
    boolean delete(String cacheName, String key);

    /**
     * Atomic GETDEL: reads the key's value and deletes it at the same time. Maps to the Redis 6.2+ GETDEL command.
     * Used in correctness-critical cases that need an atomic "read + delete" (e.g. refresh token rotation,
     * so two instances cannot both get the same token data).
     * Returns null if the key does not exist; throws RedisOperationException on Redis failure.
     */
    <T> T getAndDelete(String cacheName, String key, Class<T> clazz);

    /** One multi-key DEL for many keys of one cacheName; returns how many existed. Throws RedisOperationException on Redis failure. */
    long deleteAll(String cacheName, java.util.Collection<String> keys);

    /**
     * Uses SCAN to match the prefix and batch-deletes all matching keys (including the cacheName prefix).
     * Returns the number actually deleted, or 0 if none found. Throws RedisOperationException on Redis failure.
     */
    long deleteByPrefix(String cacheName, String keyPrefix);

    /** SET NX EX: returns true if the lock was acquired, false if the key exists; throws RedisOperationException on Redis failure. */
    boolean setIfAbsent(String cacheName, String key, Object value, Duration timeout);

    // ── Hash ─────────────────────────────────────────────────────────

    <T> T hGet(String cacheName, String key, String field, Class<T> clazz);

    /** Deletes a hash field; throws RedisOperationException on Redis failure. */
    boolean hDelete(String cacheName, String key, String field);

    Map<String, Object> hGetAll(String cacheName, String key);

    // ── Counter ───────────────────────────────────────────────────────

    /**
     * INCR + set TTL the first time (EXPIRE only when counter == 1).
     * Throws RedisOperationException on Redis failure.
     */
    long incrementAndExpire(String cacheName, String key, Duration ttl);

    /** Same as {@link #incrementAndExpire(String, String, Duration)}, adding {@code delta} (INCRBY) in one round trip. */
    long incrementAndExpire(String cacheName, String key, long delta, Duration ttl);

    // ── TTL / existence ───────────────────────────────────────────────

    boolean expire(String cacheName, String key, Duration ttl);

    boolean hasKey(String cacheName, String key);

    /** Same as {@link #hasKey}, but throws RedisOperationException on Redis failure, for checks that must fail closed (OTP lockout). */
    boolean hasKeyOrThrow(String cacheName, String key);

    // ── Transaction (MULTI/EXEC) ─────────────────────────────────────

    /**
     * P1-#17: atomically performs several correctness-critical writes in a single MULTI/EXEC transaction, collapsing several round-trips into one.
     *
     * <p>The batch is applied atomically (never partially); a connection-level failure throws {@link RedisOperationException} for the whole batch (fail-fast),
     * semantically like calling {@code setOrThrow} for each write, but stronger (atomic). Fail-soft writes
     * should not go in this transaction; the caller must write them separately with {@link #set}.</p>
     */
    void execInTransaction(java.util.List<RedisWrite> writes);

    /**
     * Refresh token rotation in one Lua script: unless the old token is gone or its session revoked, writes the consumed
     * marker, the new token, the user index entry (renewing its TTL, dropping the old entry) and the grace pair, then
     * deletes the old token. Of several callers racing on one token exactly one gets {@link RotationOutcome#ROTATED}.
     * A revoked session's old token and index entry are deleted. Throws {@link RedisOperationException} on Redis failure,
     * in which case the old token is still in place.
     */
    RotationOutcome rotateRefreshToken(RefreshRotation rotation);

    enum RotationOutcome { ROTATED, TOKEN_GONE, SESSION_REVOKED }

    /** Keys are keyOf(token) digests, as RefreshTokenService stores them. */
    record RefreshRotation(String userId, String sessionId, String oldKey, String newKey, Object newData,
                           Object gracePair, Duration refreshTtl, Duration graceTtl) {}

    /** A single write descriptor for {@link #execInTransaction}. */
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
