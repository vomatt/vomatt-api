package com.vomatt.common.redis;

import com.vomatt.common.security.RefreshTokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisServiceImpl implements RedisService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    // P5-#10: INCRBY + first PEXPIRE run atomically in one Lua script, so a crash between INCR and EXPIRE cannot leave an orphan key without TTL.
    // Eval with raw bytes (key/TTL/delta are raw bytes) so the value serializer does not quote the numeric argument and break PEXPIRE.
    private static final byte[] INCR_AND_EXPIRE_LUA = (
            "local c = redis.call('INCRBY', KEYS[1], ARGV[2]) "
            + "if c == tonumber(ARGV[2]) then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end "
            + "return c").getBytes(StandardCharsets.UTF_8);

    // Refresh token rotation (RefreshTokenService.rotate). KEYS: 1 old token, 2 revoked-session flag, 3 consumed marker,
    // 4 new token, 5 user index, 6 grace. ARGV: 1 session id, 2 new token data, 3 grace pair, 4 new index field,
    // 5 old index field, 6 index value, 7 refresh TTL ms, 8 grace TTL ms. Values arrive already serialized.
    // Returns 0 when the old token is gone, 2 when its session is revoked (the token is then dropped), 1 when rotated.
    // Grace goes after the other writes and the old token is deleted last: Redis keeps a failed script's earlier writes,
    // so a write error leaves the old token in place to retry and never exposes a pair whose token was not stored.
    private static final RedisScript<Long> ROTATE_REFRESH_LUA = RedisScript.of(
            "if redis.call('EXISTS', KEYS[1]) == 0 then return 0 end "
            + "if redis.call('EXISTS', KEYS[2]) == 1 then "
            + "  redis.call('DEL', KEYS[1]) redis.call('HDEL', KEYS[5], ARGV[5]) return 2 end "
            + "redis.call('SET', KEYS[3], ARGV[1], 'PX', ARGV[7]) "
            + "redis.call('SET', KEYS[4], ARGV[2], 'PX', ARGV[7]) "
            + "redis.call('HSET', KEYS[5], ARGV[4], ARGV[6]) "
            + "redis.call('PEXPIRE', KEYS[5], ARGV[7]) "
            + "redis.call('HDEL', KEYS[5], ARGV[5]) "
            + "redis.call('SET', KEYS[6], ARGV[3], 'PX', ARGV[8]) "
            + "redis.call('DEL', KEYS[1]) "
            + "return 1", Long.class);

    // ── Basic String value ───────────────────────────────────────────

    @Override
    public void set(String cacheName, String key, Object value, Duration timeout) {
        String cacheKey = CacheKeyUtil.buildKey(cacheName, key);
        try {
            redisTemplate.opsForValue().set(cacheKey, value, timeout);
            log.debug("Redis SET: key={}, timeout={}", cacheKey, timeout);
        } catch (Exception e) {
            log.warn("Redis SET failed, key={}: {}", cacheKey, e.getMessage());
        }
    }

    @Override
    public void setOrThrow(String cacheName, String key, Object value, Duration timeout) {
        String cacheKey = CacheKeyUtil.buildKey(cacheName, key);
        try {
            redisTemplate.opsForValue().set(cacheKey, value, timeout);
            log.debug("Redis SET (orThrow): key={}, timeout={}", cacheKey, timeout);
        } catch (Exception e) {
            throw new RedisOperationException("Redis SET failed, key=" + cacheKey, e);
        }
    }

    @Override
    public <T> T get(String cacheName, String key, Class<T> clazz) {
        try {
            return getOrThrow(cacheName, key, clazz);
        } catch (RedisOperationException e) {
            log.error("Redis GET error: key={}, error={}", CacheKeyUtil.buildKey(cacheName, key), e.getCause().getMessage());
            return null;
        }
    }

    @Override
    public <T> T get(String cacheName, String key, TypeReference<T> typeRef) {
        String cacheKey = CacheKeyUtil.buildKey(cacheName, key);
        try {
            Object value = redisTemplate.opsForValue().get(cacheKey);
            if (value == null) return null;
            return objectMapper.convertValue(value, typeRef);
        } catch (Exception e) {
            log.error("Redis GET (TypeReference) error: key={}, error={}", cacheKey, e.getMessage());
            return null;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getOrThrow(String cacheName, String key, Class<T> clazz) {
        String cacheKey = CacheKeyUtil.buildKey(cacheName, key);
        try {
            Object value = redisTemplate.opsForValue().get(cacheKey);
            if (value == null) return null;
            if (clazz.isInstance(value)) return (T) value;
            return objectMapper.convertValue(value, clazz);
        } catch (Exception e) {
            throw new RedisOperationException("Redis GET failed, key=" + cacheKey, e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getAndDelete(String cacheName, String key, Class<T> clazz) {
        String cacheKey = CacheKeyUtil.buildKey(cacheName, key);
        try {
            Object value = redisTemplate.opsForValue().getAndDelete(cacheKey);
            if (value == null) return null;
            if (clazz.isInstance(value)) return (T) value;
            return objectMapper.convertValue(value, clazz);
        } catch (Exception e) {
            throw new RedisOperationException("Redis GETDEL failed, key=" + cacheKey, e);
        }
    }

    @Override
    public boolean delete(String cacheName, String key) {
        String cacheKey = CacheKeyUtil.buildKey(cacheName, key);
        try {
            Boolean result = redisTemplate.delete(cacheKey);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            throw new RedisOperationException("Redis DELETE failed, key=" + cacheKey, e);
        }
    }

    @Override
    public long deleteAll(String cacheName, java.util.Collection<String> keys) {
        if (keys.isEmpty()) {
            return 0;
        }
        List<String> cacheKeys = keys.stream().map(k -> CacheKeyUtil.buildKey(cacheName, k)).toList();
        try {
            Long n = redisTemplate.delete(cacheKeys);
            return n == null ? 0 : n;
        } catch (Exception e) {
            throw new RedisOperationException("Redis DEL failed, cacheName=" + cacheName + ", keys=" + cacheKeys.size(), e);
        }
    }

    @Override
    public long deleteByPrefix(String cacheName, String keyPrefix) {
        // Full match pattern: cacheName:keyPrefix*
        String matchPattern = CacheKeyUtil.buildKey(cacheName, keyPrefix) + "*";
        try {
            ScanOptions options = ScanOptions.scanOptions().match(matchPattern).count(500).build();
            List<String> batch = new ArrayList<>(500);
            long deleted = 0;
            try (Cursor<String> cursor = redisTemplate.scan(options)) {
                while (cursor.hasNext()) {
                    batch.add(cursor.next());
                    if (batch.size() >= 500) {
                        Long n = redisTemplate.delete(batch);
                        if (n != null) deleted += n;
                        batch.clear();
                    }
                }
            }
            if (!batch.isEmpty()) {
                Long n = redisTemplate.delete(batch);
                if (n != null) deleted += n;
            }
            log.debug("Redis DEL by prefix: pattern={}, deleted={}", matchPattern, deleted);
            return deleted;
        } catch (Exception e) {
            throw new RedisOperationException("Redis DEL by prefix failed, pattern=" + matchPattern, e);
        }
    }

    @Override
    public boolean setIfAbsent(String cacheName, String key, Object value, Duration timeout) {
        String cacheKey = CacheKeyUtil.buildKey(cacheName, key);
        try {
            Boolean result = redisTemplate.opsForValue().setIfAbsent(cacheKey, value, timeout);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            throw new RedisOperationException("Redis SETNX failed, key=" + cacheKey, e);
        }
    }

    // ── Hash ─────────────────────────────────────────────────────────

    @Override
    @SuppressWarnings("unchecked")
    public <T> T hGet(String cacheName, String key, String field, Class<T> clazz) {
        String cacheKey = CacheKeyUtil.buildKey(cacheName, key);
        try {
            Object value = redisTemplate.opsForHash().get(cacheKey, field);
            if (value == null) return null;
            if (clazz.isInstance(value)) return (T) value;
            return objectMapper.convertValue(value, clazz);
        } catch (Exception e) {
            log.error("Redis HGET error: key={}, field={}, error={}", cacheKey, field, e.getMessage());
            return null;
        }
    }

    @Override
    public boolean hDelete(String cacheName, String key, String field) {
        String cacheKey = CacheKeyUtil.buildKey(cacheName, key);
        try {
            Long count = redisTemplate.opsForHash().delete(cacheKey, (Object) field);
            return count != null && count > 0;
        } catch (Exception e) {
            throw new RedisOperationException("Redis HDEL failed, key=" + cacheKey + ", field=" + field, e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> hGetAll(String cacheName, String key) {
        String cacheKey = CacheKeyUtil.buildKey(cacheName, key);
        try {
            return (Map<String, Object>) (Map<?, ?>) redisTemplate.opsForHash().entries(cacheKey);
        } catch (Exception e) {
            log.error("Redis HGETALL error: key={}, error={}", cacheKey, e.getMessage());
            return Map.of();
        }
    }

    // ── Counter ───────────────────────────────────────────────────────

    @Override
    public long incrementAndExpire(String cacheName, String key, Duration ttl) {
        return incrementAndExpire(cacheName, key, 1, ttl);
    }

    @Override
    @SuppressWarnings("unchecked")
    public long incrementAndExpire(String cacheName, String key, long delta, Duration ttl) {
        String cacheKey = CacheKeyUtil.buildKey(cacheName, key);
        try {
            RedisSerializer<String> keySer = (RedisSerializer<String>) redisTemplate.getKeySerializer();
            byte[] keyBytes = keySer.serialize(cacheKey);
            byte[] ttlBytes = Long.toString(ttl.toMillis()).getBytes(StandardCharsets.UTF_8);
            byte[] deltaBytes = Long.toString(delta).getBytes(StandardCharsets.UTF_8);
            // INCRBY and the first PEXPIRE run atomically in one Lua script (raw bytes, bypassing the value serializer).
            Long count = redisTemplate.execute((RedisCallback<Long>) connection -> connection.scriptingCommands()
                    .eval(INCR_AND_EXPIRE_LUA, ReturnType.INTEGER, 1, keyBytes, ttlBytes, deltaBytes));
            if (count == null) {
                throw new RedisOperationException("Redis INCR returned null, key=" + cacheKey, null);
            }
            return count;
        } catch (RedisOperationException e) {
            throw e;
        } catch (Exception e) {
            throw new RedisOperationException("Redis INCR failed, key=" + cacheKey, e);
        }
    }

    @Override
    public void execInTransaction(List<RedisWrite> writes) {
        try {
            redisTemplate.execute(new SessionCallback<Object>() {
                @Override
                @SuppressWarnings("unchecked")
                public Object execute(RedisOperations operations) {
                    RedisOperations<String, Object> ops = (RedisOperations<String, Object>) operations;
                    ops.multi();
                    for (RedisWrite w : writes) {
                        String cacheKey = CacheKeyUtil.buildKey(w.cacheName(), w.key());
                        switch (w.op()) {
                            case SET -> ops.opsForValue().set(cacheKey, w.value(), w.ttl());
                            case HPUT -> ops.opsForHash().put(cacheKey, w.field(), w.value());
                            case EXPIRE -> ops.expire(cacheKey, w.ttl());
                            case HDELETE -> ops.opsForHash().delete(cacheKey, w.field());
                        }
                    }
                    ops.exec();
                    return null;
                }
            });
        } catch (Exception e) {
            throw new RedisOperationException("Redis MULTI/EXEC transaction failed", e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public RotationOutcome rotateRefreshToken(RefreshRotation r) {
        String oldTokenKey = CacheKeyUtil.buildKey(RefreshTokenService.NAMESPACE, r.oldKey());
        try {
            // Serialized exactly as the template would, so get/hGet/hGetAll read these values back unchanged
            RedisSerializer<String> hashKeySer = (RedisSerializer<String>) redisTemplate.getHashKeySerializer();
            RedisSerializer<Object> valueSer = (RedisSerializer<Object>) redisTemplate.getValueSerializer();
            RedisSerializer<Object> hashValueSer = (RedisSerializer<Object>) redisTemplate.getHashValueSerializer();
            List<String> keys = List.of(
                    oldTokenKey,
                    CacheKeyUtil.buildKey(RefreshTokenService.REVOKED_SESSION_NS, r.sessionId()),
                    CacheKeyUtil.buildKey(RefreshTokenService.CONSUMED_NS, r.oldKey()),
                    CacheKeyUtil.buildKey(RefreshTokenService.NAMESPACE, r.newKey()),
                    CacheKeyUtil.buildKey(RefreshTokenService.USER_IDX_NS, r.userId()),
                    CacheKeyUtil.buildKey(RefreshTokenService.GRACE_NS, r.oldKey()));
            // EVALSHA, falling back to EVAL once per connection; ARGV pass through as bytes, and an integer reply is
            // returned as is, so no result serializer is needed
            Long result = redisTemplate.execute(ROTATE_REFRESH_LUA, RedisSerializer.byteArray(), null, keys,
                    valueSer.serialize(r.sessionId()),
                    valueSer.serialize(r.newData()),
                    valueSer.serialize(r.gracePair()),
                    hashKeySer.serialize(r.newKey()),
                    hashKeySer.serialize(r.oldKey()),
                    hashValueSer.serialize("1"),
                    millis(r.refreshTtl()),
                    millis(r.graceTtl()));
            if (result == null) {
                throw new RedisOperationException("Redis rotate returned null, key=" + oldTokenKey, null);
            }
            return switch (result.intValue()) {
                case 1 -> RotationOutcome.ROTATED;
                case 2 -> RotationOutcome.SESSION_REVOKED;
                default -> RotationOutcome.TOKEN_GONE;
            };
        } catch (RedisOperationException e) {
            throw e;
        } catch (Exception e) {
            throw new RedisOperationException("Redis rotate failed, key=" + oldTokenKey, e);
        }
    }

    private static byte[] millis(Duration ttl) {
        return Long.toString(ttl.toMillis()).getBytes(StandardCharsets.UTF_8);
    }

    // ── TTL / existence ───────────────────────────────────────────────

    @Override
    public boolean expire(String cacheName, String key, Duration ttl) {
        String cacheKey = CacheKeyUtil.buildKey(cacheName, key);
        try {
            Boolean result = redisTemplate.expire(cacheKey, ttl);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            log.warn("Redis EXPIRE failed, key={}: {}", cacheKey, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean hasKey(String cacheName, String key) {
        String cacheKey = CacheKeyUtil.buildKey(cacheName, key);
        try {
            Boolean result = redisTemplate.hasKey(cacheKey);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            log.warn("Redis EXISTS failed, key={}: {}", cacheKey, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean hasKeyOrThrow(String cacheName, String key) {
        String cacheKey = CacheKeyUtil.buildKey(cacheName, key);
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(cacheKey));
        } catch (Exception e) {
            throw new RedisOperationException("Redis EXISTS failed, key=" + cacheKey, e);
        }
    }
}
