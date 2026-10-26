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

    // P5-#10: INCRBY + first PEXPIRE run atomically in one Lua script, so a crash between INCR and EXPIRE cannot leave an orphan key without TTL.
    // Eval with raw bytes (key/TTL/delta are raw bytes) so the value serializer does not quote the numeric argument and break PEXPIRE.
    private static final byte[] INCR_AND_EXPIRE_LUA = (
            "local c = redis.call('INCRBY', KEYS[1], ARGV[2]) "
            + "if c == tonumber(ARGV[2]) then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end "
            + "return c").getBytes(StandardCharsets.UTF_8);

    // KEYS[1] is consumed; KEYS[2..] are the written keys, one per write, all declared as Redis requires of scripts.
    // ARGV holds each write's op and arguments in the same order. Values arrive already serialized.
    // KEYS[1] is deleted last: Redis keeps a failed script's earlier writes, so a write error leaves it in place to retry.
    private static final byte[] CONSUME_AND_EXEC_LUA = (
            "if redis.call('EXISTS', KEYS[1]) == 0 then return 0 end "
            + "local a = 1 "
            + "for k = 2, #KEYS do "
            + "  local op = ARGV[a] "
            + "  if op == 'SET' then redis.call('SET', KEYS[k], ARGV[a+1], 'PX', ARGV[a+2]) a = a + 3 "
            + "  elseif op == 'HPUT' then redis.call('HSET', KEYS[k], ARGV[a+1], ARGV[a+2]) a = a + 3 "
            + "  elseif op == 'EXPIRE' then redis.call('PEXPIRE', KEYS[k], ARGV[a+1]) a = a + 2 "
            + "  elseif op == 'HDELETE' then redis.call('HDEL', KEYS[k], ARGV[a+1]) a = a + 2 "
            + "  else return redis.error_reply('unknown op ' .. op) end "
            + "end "
            + "redis.call('DEL', KEYS[1]) "
            + "return 1").getBytes(StandardCharsets.UTF_8);

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
    public boolean consumeAndExec(String cacheName, String key, List<RedisWrite> writes) {
        String cacheKey = CacheKeyUtil.buildKey(cacheName, key);
        try {
            // Serialized exactly as the template would, so get/hGet read these values back unchanged
            RedisSerializer<String> keySer = (RedisSerializer<String>) redisTemplate.getKeySerializer();
            RedisSerializer<String> hashKeySer = (RedisSerializer<String>) redisTemplate.getHashKeySerializer();
            RedisSerializer<Object> valueSer = (RedisSerializer<Object>) redisTemplate.getValueSerializer();
            RedisSerializer<Object> hashValueSer = (RedisSerializer<Object>) redisTemplate.getHashValueSerializer();
            List<byte[]> keys = new ArrayList<>();
            List<byte[]> args = new ArrayList<>();
            keys.add(keySer.serialize(cacheKey));
            for (RedisWrite w : writes) {
                keys.add(keySer.serialize(CacheKeyUtil.buildKey(w.cacheName(), w.key())));
                args.add(w.op().name().getBytes(StandardCharsets.UTF_8));
                switch (w.op()) {
                    case SET -> {
                        args.add(valueSer.serialize(w.value()));
                        args.add(millis(w.ttl()));
                    }
                    case HPUT -> {
                        args.add(hashKeySer.serialize(w.field()));
                        args.add(hashValueSer.serialize(w.value()));
                    }
                    case EXPIRE -> args.add(millis(w.ttl()));
                    case HDELETE -> args.add(hashKeySer.serialize(w.field()));
                }
            }
            int numKeys = keys.size();
            keys.addAll(args);
            byte[][] keysAndArgs = keys.toArray(new byte[0][]);
            Long result = redisTemplate.execute((RedisCallback<Long>) connection -> connection.scriptingCommands()
                    .eval(CONSUME_AND_EXEC_LUA, ReturnType.INTEGER, numKeys, keysAndArgs));
            if (result == null) {
                throw new RedisOperationException("Redis consume returned null, key=" + cacheKey, null);
            }
            return result == 1;
        } catch (RedisOperationException e) {
            throw e;
        } catch (Exception e) {
            throw new RedisOperationException("Redis consume failed, key=" + cacheKey, e);
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
