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
 *   hGetAllOrThrow, multiGetOrThrow, hasKeyOrThrow, execInTransaction, execScript): throw RedisOperationException.
 *   A value they cannot decode (bad bytes, changed type) is not a Redis error: it is logged and read as null (a miss),
 *   and the key is left in place, so one bad value is never an outage and the caller decides what a miss means.
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

    /** Deletes hash fields; throws RedisOperationException on Redis failure. */
    boolean hDelete(String cacheName, String key, String... fields);

    Map<String, Object> hGetAll(String cacheName, String key);

    /** HGETALL; throws RedisOperationException on Redis failure instead of answering an empty map. */
    Map<String, Object> hGetAllOrThrow(String cacheName, String key);

    /** MGET: one value per key, in order, null where missing; throws RedisOperationException on Redis failure. */
    <T> java.util.List<T> multiGetOrThrow(String cacheName, java.util.List<String> keys, Class<T> clazz);

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
     * Runs a Lua script (EVALSHA, falling back to EVAL) on full Redis keys ({@link CacheKeyUtil#buildKey}) and returns
     * its integer reply. A {@code byte[]} arg is passed as is (hash fields, numbers); any other goes through the value
     * serializer, so get/hGet read it back. Throws {@link RedisOperationException} on Redis failure or an error reply.
     */
    long execScript(org.springframework.data.redis.core.script.RedisScript<Long> script, java.util.List<String> keys,
                    java.util.List<Object> args);

    /** A single write descriptor for {@link #execInTransaction}. */
    record RedisWrite(Op op, String cacheName, String key, String field, Object value, Duration ttl) {
        public enum Op { SET, HPUT, EXPIRE }

        public static RedisWrite set(String cacheName, String key, Object value, Duration ttl) {
            return new RedisWrite(Op.SET, cacheName, key, null, value, ttl);
        }

        public static RedisWrite hput(String cacheName, String key, String field, Object value) {
            return new RedisWrite(Op.HPUT, cacheName, key, field, value, null);
        }

        public static RedisWrite expire(String cacheName, String key, Duration ttl) {
            return new RedisWrite(Op.EXPIRE, cacheName, key, null, null, ttl);
        }
    }
}
