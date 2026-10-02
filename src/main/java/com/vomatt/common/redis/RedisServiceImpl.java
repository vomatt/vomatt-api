package com.vomatt.common.redis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.SessionCallback;
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

    // P5-#10：INCR + 首次 PEXPIRE 以單一 Lua 原子執行，杜絕「INCR 與 EXPIRE 之間崩潰留下無 TTL 孤兒 key」。
    // 用 raw bytes eval（key/TTL 皆為原始位元組），避免 value serializer 把數字參數加引號破壞 PEXPIRE。
    private static final byte[] INCR_AND_EXPIRE_LUA = (
            "local c = redis.call('INCR', KEYS[1]) "
            + "if c == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end "
            + "return c").getBytes(StandardCharsets.UTF_8);

    // P1-#18：HSET + 「僅在 key 尚無 TTL 時設 TTL」以單一 Lua 原子執行，
    // 取代原本「HSET → getExpire 探測 → expire」的三段（多一次 RTT + TTL 競態）。
    // value/field 用 template 既有 serializer 序列化，確保與 opsForHash().get 讀回一致。
    private static final byte[] HSET_AND_EXPIRE_LUA = (
            "redis.call('HSET', KEYS[1], ARGV[1], ARGV[2]) "
            + "if redis.call('PTTL', KEYS[1]) == -1 then redis.call('PEXPIRE', KEYS[1], ARGV[3]) end "
            + "return 1").getBytes(StandardCharsets.UTF_8);

    // ── 基本 String value ────────────────────────────────────────────

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
    @SuppressWarnings("unchecked")
    public <T> T get(String cacheName, String key, Class<T> clazz) {
        String cacheKey = CacheKeyUtil.buildKey(cacheName, key);
        try {
            Object value = redisTemplate.opsForValue().get(cacheKey);
            if (value == null) return null;
            if (clazz.isInstance(value)) return (T) value;
            return objectMapper.convertValue(value, clazz);
        } catch (Exception e) {
            log.error("Redis GET error: key={}, error={}", cacheKey, e.getMessage());
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
    public long deleteByPrefix(String cacheName, String keyPrefix) {
        // 完整匹配模式：cacheName:keyPrefix*
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
    public void hSet(String cacheName, String key, String field, Object value, Duration ttl) {
        String cacheKey = CacheKeyUtil.buildKey(cacheName, key);
        try {
            hSetAndExpireAtomic(cacheKey, field, value, ttl);
            log.debug("Redis HSET: key={}, field={}", cacheKey, field);
        } catch (Exception e) {
            log.warn("Redis HSET failed, key={}, field={}: {}", cacheKey, field, e.getMessage());
        }
    }

    @Override
    public void hSetOrThrow(String cacheName, String key, String field, Object value, Duration ttl) {
        String cacheKey = CacheKeyUtil.buildKey(cacheName, key);
        try {
            hSetAndExpireAtomic(cacheKey, field, value, ttl);
            log.debug("Redis HSET (orThrow): key={}, field={}", cacheKey, field);
        } catch (Exception e) {
            throw new RedisOperationException("Redis HSET failed, key=" + cacheKey + ", field=" + field, e);
        }
    }

    /**
     * P1-#18：以單一 Lua 原子完成 HSET + 「僅在 key 尚無 TTL 時設 TTL」，省去 getExpire 探測的額外 RTT 與競態。
     * value/field 以 template 既有 serializer 序列化，確保 {@code opsForHash().get} 能正確讀回。
     */
    @SuppressWarnings("unchecked")
    private void hSetAndExpireAtomic(String cacheKey, String field, Object value, Duration ttl) {
        RedisSerializer<String> keySer = (RedisSerializer<String>) redisTemplate.getKeySerializer();
        RedisSerializer<String> hashKeySer = (RedisSerializer<String>) redisTemplate.getHashKeySerializer();
        RedisSerializer<Object> hashValSer = (RedisSerializer<Object>) redisTemplate.getHashValueSerializer();
        byte[] keyBytes = keySer.serialize(cacheKey);
        byte[] fieldBytes = hashKeySer.serialize(field);
        byte[] valueBytes = hashValSer.serialize(value);
        byte[] ttlBytes = Long.toString(ttl.toMillis()).getBytes(StandardCharsets.UTF_8);
        redisTemplate.execute((RedisCallback<Object>) connection -> connection.scriptingCommands()
                .eval(HSET_AND_EXPIRE_LUA, ReturnType.INTEGER, 1, keyBytes, fieldBytes, valueBytes, ttlBytes));
    }

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
    @SuppressWarnings("unchecked")
    public long incrementAndExpire(String cacheName, String key, Duration ttl) {
        String cacheKey = CacheKeyUtil.buildKey(cacheName, key);
        try {
            RedisSerializer<String> keySer = (RedisSerializer<String>) redisTemplate.getKeySerializer();
            byte[] keyBytes = keySer.serialize(cacheKey);
            byte[] ttlBytes = Long.toString(ttl.toMillis()).getBytes(StandardCharsets.UTF_8);
            // INCR 與首次 PEXPIRE 以單一 Lua script 原子執行（raw bytes，不經 value serializer）。
            Long count = redisTemplate.execute((RedisCallback<Long>) connection -> connection.scriptingCommands()
                    .eval(INCR_AND_EXPIRE_LUA, ReturnType.INTEGER, 1, keyBytes, ttlBytes));
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
}
