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
    public void set(String key, Object value) {
        try {
            redisTemplate.opsForValue().set(key, value);
            log.debug("Redis SET: key={}", key);
        } catch (Exception e) {
            log.error("Redis SET error: key={}, error={}", key, e.getMessage(), e);
            throw new RuntimeException("Redis set operation failed", e);
        }
    }

    @Override
    public void set(String key, Object value, Duration timeout) {
        try {
            redisTemplate.opsForValue().set(key, value, timeout);
            log.debug("Redis SET with timeout: key={}, timeout={}", key, timeout);
        } catch (Exception e) {
            log.error("Redis SET with timeout error: key={}, timeout={}, error={}", key, timeout, e.getMessage(), e);
            throw new RuntimeException("Redis set with timeout operation failed", e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> clazz) {
        try {
            Object value = redisTemplate.opsForValue().get(key);
            if (value == null) {
                return null;
            }

            if (clazz.isInstance(value)) {
                return (T) value;
            }

            // 使用 ObjectMapper 進行類型轉換
            return objectMapper.convertValue(value, clazz);
        } catch (Exception e) {
            log.error("Redis GET error: key={}, class={}, error={}", key, clazz.getSimpleName(), e.getMessage(), e);
            return null;
        }
    }

    @Override
    public boolean delete(String key) {
        try {
            Boolean result = redisTemplate.delete(key);
            log.debug("Redis DELETE: key={}, result={}", key, result);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            log.error("Redis DELETE error: key={}, error={}", key, e.getMessage(), e);
            return false;
        }
    }

    @Override
    public boolean hasKey(String key) {
        try {
            Boolean result = redisTemplate.hasKey(key);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            log.error("Redis HASKEY error: key={}, error={}", key, e.getMessage(), e);
            return false;
        }
    }

    @Override
    public boolean expire(String key, Duration timeout) {
        try {
            Boolean result = redisTemplate.expire(key, timeout);
            log.debug("Redis EXPIRE: key={}, timeout={}, result={}", key, timeout, result);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            log.error("Redis EXPIRE error: key={}, timeout={}, error={}", key, timeout, e.getMessage(), e);
            return false;
        }
    }

    @Override
    public Duration getExpire(String key) {
        try {
            Long expire = redisTemplate.getExpire(key, TimeUnit.SECONDS);
            return expire != null && expire > 0 ? Duration.ofSeconds(expire) : null;
        } catch (Exception e) {
            log.error("Redis GETEXPIRE error: key={}, error={}", key, e.getMessage(), e);
            return null;
        }
    }

    // ==================== 批量操作 ====================

    @Override
    public void multiSet(Map<String, Object> keyValueMap) {
        try {
            redisTemplate.opsForValue().multiSet(keyValueMap);
            log.debug("Redis MULTISET: count={}", keyValueMap.size());
        } catch (Exception e) {
            log.error("Redis MULTISET error: count={}, error={}", keyValueMap.size(), e.getMessage(), e);
            throw new RuntimeException("Redis multi set operation failed", e);
        }
    }

    @Override
    public void multiSet(Map<String, Object> keyValueMap, Duration timeout) {
        try {
            // Redis 不支援批量設置過期時間，需要分別設置
            redisTemplate.executePipelined((RedisCallback<?>) connection -> {
                keyValueMap.forEach((key, value) -> {
                    redisTemplate.opsForValue().set(key, value, timeout);
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
    public <T> Map<String, T> multiGet(Set<String> keys, Class<T> clazz) {
        try {
            List<Object> values = redisTemplate.opsForValue().multiGet(keys);
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
    public long multiDelete(Set<String> keys) {
        try {
            Long result = redisTemplate.delete(keys);
            log.debug("Redis MULTIDELETE: requested={}, deleted={}", keys.size(), result);
            return result != null ? result : 0;
        } catch (Exception e) {
            log.error("Redis MULTIDELETE error: keys={}, error={}", keys.size(), e.getMessage(), e);
            return 0;
        }
    }

    @Override
    public Map<String, Boolean> multiHasKey(Set<String> keys) {
        try {
            Map<String, Boolean> result = new HashMap<>();
            for (String key : keys) {
                result.put(key, hasKey(key));
            }
            return result;
        } catch (Exception e) {
            log.error("Redis MULTIHASKEY error: keys={}, error={}", keys.size(), e.getMessage(), e);
            return new HashMap<>();
        }
    }

    // ==================== Hash 操作 ====================

    @Override
    public void hSet(String key, String hashKey, Object value) {
        try {
            redisTemplate.opsForHash().put(key, hashKey, value);
            log.debug("Redis HSET: key={}, hashKey={}", key, hashKey);
        } catch (Exception e) {
            log.error("Redis HSET error: key={}, hashKey={}, error={}", key, hashKey, e.getMessage(), e);
            throw new RuntimeException("Redis hash set operation failed", e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T hGet(String key, String hashKey, Class<T> clazz) {
        try {
            Object value = redisTemplate.opsForHash().get(key, hashKey);
            if (value == null) {
                return null;
            }

            if (clazz.isInstance(value)) {
                return (T) value;
            }

            return objectMapper.convertValue(value, clazz);
        } catch (Exception e) {
            log.error("Redis HGET error: key={}, hashKey={}, class={}, error={}", key, hashKey, clazz.getSimpleName(),
                    e.getMessage(), e);
            return null;
        }
    }

    @Override
    public void hMultiSet(String key, Map<String, Object> hashMap) {
        try {
            redisTemplate.opsForHash().putAll(key, hashMap);
            log.debug("Redis HMULTISET: key={}, count={}", key, hashMap.size());
        } catch (Exception e) {
            log.error("Redis HMULTISET error: key={}, count={}, error={}", key, hashMap.size(), e.getMessage(), e);
            throw new RuntimeException("Redis hash multi set operation failed", e);
        }
    }

    @Override
    public <T> Map<String, T> hMultiGet(String key, Set<String> hashKeys, Class<T> clazz) {
        try {
            List<Object> values = redisTemplate.opsForHash()
                    .multiGet(key, (Collection<Object>) (Collection<?>) hashKeys);
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
            log.error("Redis HMULTIGET error: key={}, hashKeys={}, class={}, error={}", key, hashKeys.size(),
                    clazz.getSimpleName(), e.getMessage(), e);
            return new HashMap<>();
        }
    }

    @Override
    public boolean hDelete(String key, String hashKey) {
        try {
            Long result = redisTemplate.opsForHash().delete(key, hashKey);
            return result != null && result > 0;
        } catch (Exception e) {
            log.error("Redis HDELETE error: key={}, hashKey={}, error={}", key, hashKey, e.getMessage(), e);
            return false;
        }
    }

    @Override
    public <T> Map<String, T> hGetAll(String key, Class<T> clazz) {
        try {
            Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);
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
            log.error("Redis HGETALL error: key={}, class={}, error={}", key, clazz.getSimpleName(), e.getMessage(), e);
            return new HashMap<>();
        }
    }

    // ==================== List 操作 ====================

    @Override
    public long leftPush(String key, Object value) {
        try {
            Long result = redisTemplate.opsForList().leftPush(key, value);
            return result != null ? result : 0;
        } catch (Exception e) {
            log.error("Redis LEFTPUSH error: key={}, error={}", key, e.getMessage(), e);
            return 0;
        }
    }

    @Override
    public long rightPush(String key, Object value) {
        try {
            Long result = redisTemplate.opsForList().rightPush(key, value);
            return result != null ? result : 0;
        } catch (Exception e) {
            log.error("Redis RIGHTPUSH error: key={}, error={}", key, e.getMessage(), e);
            return 0;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T leftPop(String key, Class<T> clazz) {
        try {
            Object value = redisTemplate.opsForList().leftPop(key);
            if (value == null) {
                return null;
            }

            if (clazz.isInstance(value)) {
                return (T) value;
            }

            return objectMapper.convertValue(value, clazz);
        } catch (Exception e) {
            log.error("Redis LEFTPOP error: key={}, class={}, error={}", key, clazz.getSimpleName(), e.getMessage(), e);
            return null;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T rightPop(String key, Class<T> clazz) {
        try {
            Object value = redisTemplate.opsForList().rightPop(key);
            if (value == null) {
                return null;
            }

            if (clazz.isInstance(value)) {
                return (T) value;
            }

            return objectMapper.convertValue(value, clazz);
        } catch (Exception e) {
            log.error("Redis RIGHTPOP error: key={}, class={}, error={}", key, clazz.getSimpleName(), e.getMessage(),
                    e);
            return null;
        }
    }

    @Override
    public long leftPushAll(String key, List<Object> values) {
        try {
            Long result = redisTemplate.opsForList().leftPushAll(key, values.toArray());
            return result != null ? result : 0;
        } catch (Exception e) {
            log.error("Redis LEFTPUSHALL error: key={}, count={}, error={}", key, values.size(), e.getMessage(), e);
            return 0;
        }
    }

    @Override
    public long rightPushAll(String key, List<Object> values) {
        try {
            Long result = redisTemplate.opsForList().rightPushAll(key, values.toArray());
            return result != null ? result : 0;
        } catch (Exception e) {
            log.error("Redis RIGHTPUSHALL error: key={}, count={}, error={}", key, values.size(), e.getMessage(), e);
            return 0;
        }
    }

    @Override
    public <T> List<T> getRange(String key, long start, long end, Class<T> clazz) {
        try {
            List<Object> values = redisTemplate.opsForList().range(key, start, end);
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
            log.error("Redis GETRANGE error: key={}, start={}, end={}, class={}, error={}", key, start, end,
                    clazz.getSimpleName(), e.getMessage(), e);
            return List.of();
        }
    }

    @Override
    public long getListSize(String key) {
        try {
            Long size = redisTemplate.opsForList().size(key);
            return size != null ? size : 0;
        } catch (Exception e) {
            log.error("Redis GETLISTSIZE error: key={}, error={}", key, e.getMessage(), e);
            return 0;
        }
    }

    // ==================== Set 操作 ====================

    @Override
    public long setAdd(String key, Object... values) {
        try {
            Long result = redisTemplate.opsForSet().add(key, values);
            return result != null ? result : 0;
        } catch (Exception e) {
            log.error("Redis SETADD error: key={}, count={}, error={}", key, values.length, e.getMessage(), e);
            return 0;
        }
    }

    @Override
    public long setRemove(String key, Object... values) {
        try {
            Long result = redisTemplate.opsForSet().remove(key, values);
            return result != null ? result : 0;
        } catch (Exception e) {
            log.error("Redis SETREMOVE error: key={}, count={}, error={}", key, values.length, e.getMessage(), e);
            return 0;
        }
    }

    @Override
    public boolean setIsMember(String key, Object value) {
        try {
            Boolean result = redisTemplate.opsForSet().isMember(key, value);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            log.error("Redis SETISMEMBER error: key={}, error={}", key, e.getMessage(), e);
            return false;
        }
    }

    @Override
    public <T> Set<T> setMembers(String key, Class<T> clazz) {
        try {
            Set<Object> members = redisTemplate.opsForSet().members(key);
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
            log.error("Redis SETMEMBERS error: key={}, class={}, error={}", key, clazz.getSimpleName(), e.getMessage(),
                    e);
            return Set.of();
        }
    }

    @Override
    public long setSize(String key) {
        try {
            Long size = redisTemplate.opsForSet().size(key);
            return size != null ? size : 0;
        } catch (Exception e) {
            log.error("Redis SETSIZE error: key={}, error={}", key, e.getMessage(), e);
            return 0;
        }
    }

    // ==================== 計數器操作 ====================

    @Override
    public long increment(String key) {
        try {
            Long result = redisTemplate.opsForValue().increment(key);
            return result != null ? result : 0;
        } catch (Exception e) {
            log.error("Redis INCREMENT error: key={}, error={}", key, e.getMessage(), e);
            return 0;
        }
    }

    @Override
    public long increment(String key, long delta) {
        try {
            Long result = redisTemplate.opsForValue().increment(key, delta);
            return result != null ? result : 0;
        } catch (Exception e) {
            log.error("Redis INCREMENT error: key={}, delta={}, error={}", key, delta, e.getMessage(), e);
            return 0;
        }
    }

    @Override
    public long decrement(String key) {
        try {
            Long result = redisTemplate.opsForValue().decrement(key);
            return result != null ? result : 0;
        } catch (Exception e) {
            log.error("Redis DECREMENT error: key={}, error={}", key, e.getMessage(), e);
            return 0;
        }
    }

    @Override
    public long decrement(String key, long delta) {
        try {
            Long result = redisTemplate.opsForValue().decrement(key, delta);
            return result != null ? result : 0;
        } catch (Exception e) {
            log.error("Redis DECREMENT error: key={}, delta={}, error={}", key, delta, e.getMessage(), e);
            return 0;
        }
    }

    // ==================== 模式匹配操作 ====================

    @Override
    public Set<String> keys(String pattern) {
        try {
            Set<String> keys = redisTemplate.keys(pattern);
            return keys != null ? keys : Set.of();
        } catch (Exception e) {
            log.error("Redis KEYS error: pattern={}, error={}", pattern, e.getMessage(), e);
            return Set.of();
        }
    }

    @Override
    public long deleteByPattern(String pattern) {
        try {
            Set<String> keys = keys(pattern);
            if (keys.isEmpty()) {
                return 0;
            }
            return multiDelete(keys);
        } catch (Exception e) {
            log.error("Redis DELETEBYPATTERN error: pattern={}, error={}", pattern, e.getMessage(), e);
            return 0;
        }
    }
}
