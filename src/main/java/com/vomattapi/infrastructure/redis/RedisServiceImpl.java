package com.vomattapi.infrastructure.redis;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * Redis 服務實現類 提供高效能的緩存操作，支援序列化和批量操作
 */
@Slf4j
@Service
public class RedisServiceImpl implements RedisService {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    // ==================== 單筆操作 ====================

    @Override
    public String buildCacheKey(String cacheName, String key) {
        return cacheName + ":" + key;
    }

    @Override
    public void set(String cacheName, String key, Object value) {
        String cacheKey = buildCacheKey(cacheName, key);
        try {
            redisTemplate.opsForValue().set(cacheKey, value);
            log.debug("Redis SET: key={}", cacheKey);
        } catch (Exception e) {
            log.error("Redis SET error: key={}, error={}", cacheKey, e.getMessage(), e);
            throw new RuntimeException("Redis set operation failed", e);
        }
    }

    @Override
    public void set(String cacheName, String key, Object value, Duration timeout) {
        String cacheKey = buildCacheKey(cacheName, key);
        try {
            redisTemplate.opsForValue().set(cacheKey, value, timeout);
            log.debug("Redis SET with timeout: key={}, timeout={}", cacheKey, timeout);
        } catch (Exception e) {
            log.error("Redis SET with timeout error: key={}, timeout={}, error={}", cacheKey, timeout, e.getMessage(), e);
            throw new RuntimeException("Redis set with timeout operation failed", e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(String cacheName, String key, Class<T> clazz) {
        String cacheKey = buildCacheKey(cacheName, key);
        try {
            Object value = redisTemplate.opsForValue().get(cacheKey);
            if (value == null) {
                return null;
            }

            if (clazz.isInstance(value)) {
                return (T) value;
            }

            // 使用 ObjectMapper 進行類型轉換
            return objectMapper.convertValue(value, clazz);
        } catch (Exception e) {
            log.error("Redis GET error: key={}, class={}, error={}", cacheKey, clazz.getSimpleName(), e.getMessage(), e);
            return null;
        }
    }

    @Override
    public boolean delete(String cacheName, String key) {
        String cacheKey = buildCacheKey(cacheName, key);
        try {
            Boolean result = redisTemplate.delete(cacheKey);
            log.debug("Redis DELETE: key={}, result={}", cacheKey, result);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            log.error("Redis DELETE error: key={}, error={}", cacheKey, e.getMessage(), e);
            return false;
        }
    }

    @Override
    public boolean hasKey(String cacheName, String key) {
        String cacheKey = buildCacheKey(cacheName, key);
        try {
            Boolean result = redisTemplate.hasKey(cacheKey);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            log.error("Redis HASKEY error: key={}, error={}", cacheKey, e.getMessage(), e);
            return false;
        }
    }

    @Override
    public boolean expire(String cacheName, String key, Duration timeout) {
        String cacheKey = buildCacheKey(cacheName, key);
        try {
            Boolean result = redisTemplate.expire(cacheKey, timeout);
            log.debug("Redis EXPIRE: key={}, timeout={}, result={}", cacheKey, timeout, result);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            log.error("Redis EXPIRE error: key={}, timeout={}, error={}", cacheKey, timeout, e.getMessage(), e);
            return false;
        }
    }

    @Override
    public Duration getExpire(String cacheName, String key) {
        String cacheKey = buildCacheKey(cacheName, key);
        try {
            Long expire = redisTemplate.getExpire(cacheKey, TimeUnit.SECONDS);
            return expire != null && expire > 0 ? Duration.ofSeconds(expire) : null;
        } catch (Exception e) {
            log.error("Redis GETEXPIRE error: key={}, error={}", cacheKey, e.getMessage(), e);
            return null;
        }
    }

    // ==================== 批量操作 ====================

    @Override
    public void multiSet(String cacheName, Map<String, Object> keyValueMap) {
        Map<String, Object> cacheKeyValueMap = new HashMap<>();
        keyValueMap.forEach((key, value) -> {
            cacheKeyValueMap.put(buildCacheKey(cacheName, key), value);
        });
        try {
            redisTemplate.opsForValue().multiSet(cacheKeyValueMap);
            log.debug("Redis MULTISET: count={}", keyValueMap.size());
        } catch (Exception e) {
            log.error("Redis MULTISET error: count={}, error={}", keyValueMap.size(), e.getMessage(), e);
            throw new RuntimeException("Redis multi set operation failed", e);
        }
    }

    @Override
    public void multiSet(String cacheName, Map<String, Object> keyValueMap, Duration timeout) {
        try {
            // Redis 不支援批量設置過期時間，需要分別設置
            redisTemplate.executePipelined((RedisCallback<?>) connection -> {
                keyValueMap.forEach((key, value) -> {
                    String cacheKey = buildCacheKey(cacheName, key);
                    redisTemplate.opsForValue().set(cacheKey, value, timeout);
                });
                return null;
            });

            log.debug("Redis MULTISET with timeout: count={}, timeout={}", keyValueMap.size(), timeout);
        } catch (Exception e) {
            log.error("Redis MULTISET with timeout error: count={}, timeout={}, error={}", keyValueMap.size(), timeout,
                    e.getMessage(), e);
            throw new RuntimeException("Redis multi set with timeout operation failed", e);
        }
    }

    @Override
    public <T> Map<String, T> multiGet(String cacheName, Set<String> keys, Class<T> clazz) {
        Set<String> cacheKeys = keys.stream()
                .map(key -> buildCacheKey(cacheName, key))
                .collect(Collectors.toSet());
        try {
            List<Object> values = redisTemplate.opsForValue().multiGet(cacheKeys);
            Map<String, T> result = new HashMap<>();

            int index = 0;
            for (String key : keys) {
                if (values != null && index < values.size()) {
                    Object value = values.get(index);
                    if (value != null) {
                        if (clazz.isInstance(value)) {
                            result.put(key, clazz.cast(value));
                        } else {
                            result.put(key, objectMapper.convertValue(value, clazz));
                        }
                    }
                }
                index++;
            }

            log.debug("Redis MULTIGET: requested={}, found={}", keys.size(), result.size());
            return result;
        } catch (Exception e) {
            log.error("Redis MULTIGET error: keys={}, class={}, error={}", keys.size(), clazz.getSimpleName(),
                    e.getMessage(), e);
            return new HashMap<>();
        }
    }

    @Override
    public long multiDelete(String cacheName, Set<String> keys) {
        Set<String> cacheKeys = keys.stream()
                .map(key -> buildCacheKey(cacheName, key))
                .collect(Collectors.toSet());
        try {
            Long result = redisTemplate.delete(cacheKeys);
            log.debug("Redis MULTIDELETE: requested={}, deleted={}", keys.size(), result);
            return result != null ? result : 0;
        } catch (Exception e) {
            log.error("Redis MULTIDELETE error: keys={}, error={}", keys.size(), e.getMessage(), e);
            return 0;
        }
    }

    @Override
    public Map<String, Boolean> multiHasKey(String cacheName, Set<String> keys) {
        try {
            Map<String, Boolean> result = new HashMap<>();
            for (String key : keys) {
                result.put(key, hasKey(cacheName, key));
            }
            return result;
        } catch (Exception e) {
            log.error("Redis MULTIHASKEY error: keys={}, error={}", keys.size(), e.getMessage(), e);
            return new HashMap<>();
        }
    }



    @Override
    public void hSet(String cacheName, String key, String hashKey, Object value) {
        String cacheKey = buildCacheKey(cacheName, key);
        try {
            redisTemplate.opsForHash().put(cacheKey, hashKey, value);
            log.debug("Redis HSET: key={}, hashKey={}", cacheKey, hashKey);
        } catch (Exception e) {
            log.error("Redis HSET error: key={}, hashKey={}, error={}", cacheKey, hashKey, e.getMessage(), e);
            throw new RuntimeException("Redis hash set operation failed", e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T hGet(String cacheName, String key, String hashKey, Class<T> clazz) {
        String cacheKey = buildCacheKey(cacheName, key);
        try {
            Object value = redisTemplate.opsForHash().get(cacheKey, hashKey);
            if (value == null) {
                return null;
            }

            if (clazz.isInstance(value)) {
                return (T) value;
            }

            return objectMapper.convertValue(value, clazz);
        } catch (Exception e) {
            log.error("Redis HGET error: key={}, hashKey={}, class={}, error={}", cacheKey, hashKey, clazz.getSimpleName(),
                    e.getMessage(), e);
            return null;
        }
    }

    @Override
    public void hMultiSet(String cacheName, String key, Map<String, Object> hashMap) {
        String cacheKey = buildCacheKey(cacheName, key);
        try {
            redisTemplate.opsForHash().putAll(cacheKey, hashMap);
            log.debug("Redis HMULTISET: key={}, count={}", cacheKey, hashMap.size());
        } catch (Exception e) {
            log.error("Redis HMULTISET error: key={}, count={}, error={}", cacheKey, hashMap.size(), e.getMessage(), e);
            throw new RuntimeException("Redis hash multi set operation failed", e);
        }
    }

    @Override
    public <T> Map<String, T> hMultiGet(String cacheName, String key, Set<String> hashKeys, Class<T> clazz) {
        String cacheKey = buildCacheKey(cacheName, key);
        try {
            List<Object> values = redisTemplate.opsForHash()
                    .multiGet(cacheKey, (Collection<Object>) (Collection<?>) hashKeys);
            Map<String, T> result = new HashMap<>();

            int index = 0;
            for (String hashKey : hashKeys) {
                if (values != null && index < values.size()) {
                    Object value = values.get(index);
                    if (value != null) {
                        if (clazz.isInstance(value)) {
                            result.put(hashKey, clazz.cast(value));
                        } else {
                            result.put(hashKey, objectMapper.convertValue(value, clazz));
                        }
                    }
                }
                index++;
            }

            return result;
        } catch (Exception e) {
            log.error("Redis HMULTIGET error: key={}, hashKeys={}, class={}, error={}", cacheKey, hashKeys.size(),
                    clazz.getSimpleName(), e.getMessage(), e);
            return new HashMap<>();
        }
    }

    @Override
    public boolean hDelete(String cacheName, String key, String hashKey) {
        String cacheKey = buildCacheKey(cacheName, key);
        try {
            Long result = redisTemplate.opsForHash().delete(cacheKey, hashKey);
            return result != null && result > 0;
        } catch (Exception e) {
            log.error("Redis HDELETE error: key={}, hashKey={}, error={}", cacheKey, hashKey, e.getMessage(), e);
            return false;
        }
    }

    @Override
    public <T> Map<String, T> hGetAll(String cacheName, String key, Class<T> clazz) {
        String cacheKey = buildCacheKey(cacheName, key);
        try {
            Map<Object, Object> entries = redisTemplate.opsForHash().entries(cacheKey);
            Map<String, T> result = new HashMap<>();

            for (Map.Entry<Object, Object> entry : entries.entrySet()) {
                String hashKey = String.valueOf(entry.getKey());
                Object value = entry.getValue();

                if (value != null) {
                    if (clazz.isInstance(value)) {
                        result.put(hashKey, clazz.cast(value));
                    } else {
                        result.put(hashKey, objectMapper.convertValue(value, clazz));
                    }
                }
            }

            return result;
        } catch (Exception e) {
            log.error("Redis HGETALL error: key={}, class={}, error={}", cacheKey, clazz.getSimpleName(), e.getMessage(), e);
            return new HashMap<>();
        }
    }

    // ==================== List 操作 ====================

    @Override
    public long leftPush(String cacheName, String key, Object value) {
        String cacheKey = buildCacheKey(cacheName, key);
        try {
            Long result = redisTemplate.opsForList().leftPush(cacheKey, value);
            return result != null ? result : 0;
        } catch (Exception e) {
            log.error("Redis LEFTPUSH error: key={}, error={}", cacheKey, e.getMessage(), e);
            return 0;
        }
    }

    @Override
    public long rightPush(String cacheName, String key, Object value) {
        String cacheKey = buildCacheKey(cacheName, key);
        try {
            Long result = redisTemplate.opsForList().rightPush(cacheKey, value);
            return result != null ? result : 0;
        } catch (Exception e) {
            log.error("Redis RIGHTPUSH error: key={}, error={}", cacheKey, e.getMessage(), e);
            return 0;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T leftPop(String cacheName, String key, Class<T> clazz) {
        String cacheKey = buildCacheKey(cacheName, key);
        try {
            Object value = redisTemplate.opsForList().leftPop(cacheKey);
            if (value == null) {
                return null;
            }

            if (clazz.isInstance(value)) {
                return (T) value;
            }

            return objectMapper.convertValue(value, clazz);
        } catch (Exception e) {
            log.error("Redis LEFTPOP error: key={}, class={}, error={}", cacheKey, clazz.getSimpleName(), e.getMessage(), e);
            return null;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T rightPop(String cacheName, String key, Class<T> clazz) {
        String cacheKey = buildCacheKey(cacheName, key);
        try {
            Object value = redisTemplate.opsForList().rightPop(cacheKey);
            if (value == null) {
                return null;
            }

            if (clazz.isInstance(value)) {
                return (T) value;
            }

            return objectMapper.convertValue(value, clazz);
        } catch (Exception e) {
            log.error("Redis RIGHTPOP error: key={}, class={}, error={}", cacheKey, clazz.getSimpleName(), e.getMessage(),
                    e);
            return null;
        }
    }

    @Override
    public long leftPushAll(String cacheName, String key, List<Object> values) {
        String cacheKey = buildCacheKey(cacheName, key);
        try {
            Long result = redisTemplate.opsForList().leftPushAll(cacheKey, values.toArray());
            return result != null ? result : 0;
        } catch (Exception e) {
            log.error("Redis LEFTPUSHALL error: key={}, count={}, error={}", cacheKey, values.size(), e.getMessage(), e);
            return 0;
        }
    }

    @Override
    public long rightPushAll(String cacheName, String key, List<Object> values) {
        String cacheKey = buildCacheKey(cacheName, key);
        try {
            Long result = redisTemplate.opsForList().rightPushAll(cacheKey, values.toArray());
            return result != null ? result : 0;
        } catch (Exception e) {
            log.error("Redis RIGHTPUSHALL error: key={}, count={}, error={}", cacheKey, values.size(), e.getMessage(), e);
            return 0;
        }
    }

    @Override
    public <T> List<T> getRange(String cacheName, String key, long start, long end, Class<T> clazz) {
        String cacheKey = buildCacheKey(cacheName, key);
        try {
            List<Object> values = redisTemplate.opsForList().range(cacheKey, start, end);
            if (values == null) {
                return List.of();
            }

            return values.stream().filter(value -> value != null).map(value -> {
                if (clazz.isInstance(value)) {
                    return clazz.cast(value);
                }
                return objectMapper.convertValue(value, clazz);
            }).collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Redis GETRANGE error: key={}, start={}, end={}, class={}, error={}", cacheKey, start, end,
                    clazz.getSimpleName(), e.getMessage(), e);
            return List.of();
        }
    }

    @Override
    public long getListSize(String cacheName, String key) {
        String cacheKey = buildCacheKey(cacheName, key);
        try {
            Long size = redisTemplate.opsForList().size(cacheKey);
            return size != null ? size : 0;
        } catch (Exception e) {
            log.error("Redis GETLISTSIZE error: key={}, error={}", cacheKey, e.getMessage(), e);
            return 0;
        }
    }

    // ==================== Set 操作 ====================

    @Override
    public long setAdd(String cacheName, String key, Object... values) {
        String cacheKey = buildCacheKey(cacheName, key);
        try {
            Long result = redisTemplate.opsForSet().add(cacheKey, values);
            return result != null ? result : 0;
        } catch (Exception e) {
            log.error("Redis SETADD error: key={}, count={}, error={}", cacheKey, values.length, e.getMessage(), e);
            return 0;
        }
    }

    @Override
    public long setRemove(String cacheName, String key, Object... values) {
        String cacheKey = buildCacheKey(cacheName, key);
        try {
            Long result = redisTemplate.opsForSet().remove(cacheKey, values);
            return result != null ? result : 0;
        } catch (Exception e) {
            log.error("Redis SETREMOVE error: key={}, count={}, error={}", cacheKey, values.length, e.getMessage(), e);
            return 0;
        }
    }

    @Override
    public boolean setIsMember(String cacheName, String key, Object value) {
        String cacheKey = buildCacheKey(cacheName, key);
        try {
            Boolean result = redisTemplate.opsForSet().isMember(cacheKey, value);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            log.error("Redis SETISMEMBER error: key={}, error={}", cacheKey, e.getMessage(), e);
            return false;
        }
    }

    @Override
    public <T> Set<T> setMembers(String cacheName, String key, Class<T> clazz) {
        String cacheKey = buildCacheKey(cacheName, key);
        try {
            Set<Object> members = redisTemplate.opsForSet().members(cacheKey);
            if (members == null) {
                return Set.of();
            }

            return members.stream().filter(member -> member != null).map(member -> {
                if (clazz.isInstance(member)) {
                    return clazz.cast(member);
                }
                return objectMapper.convertValue(member, clazz);
            }).collect(Collectors.toSet());
        } catch (Exception e) {
            log.error("Redis SETMEMBERS error: key={}, class={}, error={}", cacheKey, clazz.getSimpleName(), e.getMessage(),
                    e);
            return Set.of();
        }
    }

    @Override
    public long setSize(String cacheName, String key) {
        String cacheKey = buildCacheKey(cacheName, key);
        try {
            Long size = redisTemplate.opsForSet().size(cacheKey);
            return size != null ? size : 0;
        } catch (Exception e) {
            log.error("Redis SETSIZE error: key={}, error={}", cacheKey, e.getMessage(), e);
            return 0;
        }
    }

    // ==================== 計數器操作 ====================

    @Override
    public long increment(String cacheName, String key) {
        String cacheKey = buildCacheKey(cacheName, key);
        try {
            Long result = redisTemplate.opsForValue().increment(cacheKey);
            return result != null ? result : 0;
        } catch (Exception e) {
            log.error("Redis INCREMENT error: key={}, error={}", cacheKey, e.getMessage(), e);
            return 0;
        }
    }

    @Override
    public long increment(String cacheName, String key, long delta) {
        String cacheKey = buildCacheKey(cacheName, key);
        try {
            Long result = redisTemplate.opsForValue().increment(cacheKey, delta);
            return result != null ? result : 0;
        } catch (Exception e) {
            log.error("Redis INCREMENT error: key={}, delta={}, error={}", cacheKey, delta, e.getMessage(), e);
            return 0;
        }
    }

    @Override
    public long decrement(String cacheName, String key) {
        String cacheKey = buildCacheKey(cacheName, key);
        try {
            Long result = redisTemplate.opsForValue().decrement(cacheKey);
            return result != null ? result : 0;
        } catch (Exception e) {
            log.error("Redis DECREMENT error: key={}, error={}", cacheKey, e.getMessage(), e);
            return 0;
        }
    }

    @Override
    public long decrement(String cacheName, String key, long delta) {
        String cacheKey = buildCacheKey(cacheName, key);
        try {
            Long result = redisTemplate.opsForValue().decrement(cacheKey, delta);
            return result != null ? result : 0;
        } catch (Exception e) {
            log.error("Redis DECREMENT error: key={}, delta={}, error={}", cacheKey, delta, e.getMessage(), e);
            return 0;
        }
    }

    // ==================== 模式匹配操作 ====================

    @Override
    public Set<String> keys(String cacheName, String pattern) {
        String cachePattern = buildCacheKey(cacheName, pattern);
        try {
            Set<String> keys = redisTemplate.keys(cachePattern);
            return keys != null ? keys : Set.of();
        } catch (Exception e) {
            log.error("Redis KEYS error: pattern={}, error={}", cachePattern, e.getMessage(), e);
            return Set.of();
        }
    }

    @Override
    public long deleteByPattern(String cacheName, String pattern) {
        try {
            Set<String> keys = keys(cacheName, pattern);
            if (keys.isEmpty()) {
                return 0;
            }
            // For deleteByPattern, we need to delete the actual cache keys, not build new ones
            Long result = redisTemplate.delete(keys);
            return result != null ? result : 0;
        } catch (Exception e) {
            log.error("Redis DELETEBYPATTERN error: pattern={}, error={}", pattern, e.getMessage(), e);
            return 0;
        }
    }
}
