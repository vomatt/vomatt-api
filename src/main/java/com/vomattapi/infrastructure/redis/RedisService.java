package com.vomattapi.infrastructure.redis;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Redis 服務接口
 * 提供單筆和批量的讀取寫入操作，使用序列化提升性能
 */
public interface RedisService {

    // ==================== 單筆操作 ====================
    /**
     * 儲存鍵值對
     */
    void set(String cacheName, String key, Object value);

    /**
     * 儲存鍵值對並設置過期時間
     */
    void set(String cacheName, String key, Object value, Duration timeout);

    /**
     * 獲取值
     */
    <T> T get(String cacheName, String key, Class<T> clazz);

    /**
     * 刪除鍵
     */
    boolean delete(String cacheName, String key);

    /**
     * 檢查鍵是否存在
     */
    boolean hasKey(String cacheName, String key);

    /**
     * 設置過期時間
     */
    boolean expire(String cacheName, String key, Duration timeout);

    /**
     * 獲取過期時間
     */
    Duration getExpire(String cacheName, String key);

    // ==================== 批量操作 ====================

    /**
     * 批量儲存鍵值對
     */
    void multiSet(String cacheName, Map<String, Object> keyValueMap);

    /**
     * 批量儲存鍵值對並設置過期時間
     */
    void multiSet(String cacheName, Map<String, Object> keyValueMap, Duration timeout);

    /**
     * 批量獲取值
     */
    <T> Map<String, T> multiGet(String cacheName, Set<String> keys, Class<T> clazz);

    /**
     * 批量刪除鍵
     */
    long multiDelete(String cacheName, Set<String> keys);

    /**
     * 批量檢查鍵是否存在
     */
    Map<String, Boolean> multiHasKey(String cacheName, Set<String> keys);

    // ==================== Hash 操作 ====================

    /**
     * Hash 儲存
     */
    void hSet(String cacheName, String key, String hashKey, Object value);

    /**
     * Hash 獲取
     */
    <T> T hGet(String cacheName, String key, String hashKey, Class<T> clazz);

    /**
     * Hash 批量儲存
     */
    void hMultiSet(String cacheName, String key, Map<String, Object> hashMap);

    /**
     * Hash 批量獲取
     */
    <T> Map<String, T> hMultiGet(String cacheName, String key, Set<String> hashKeys, Class<T> clazz);

    /**
     * Hash 刪除字段
     */
    boolean hDelete(String cacheName, String key, String hashKey);

    /**
     * Hash 獲取所有字段和值
     */
    <T> Map<String, T> hGetAll(String cacheName, String key, Class<T> clazz);

    // ==================== List 操作 ====================

    /**
     * List 左側推入
     */
    long leftPush(String cacheName, String key, Object value);

    /**
     * List 右側推入
     */
    long rightPush(String cacheName, String key, Object value);

    /**
     * List 左側彈出
     */
    <T> T leftPop(String cacheName, String key, Class<T> clazz);

    /**
     * List 右側彈出
     */
    <T> T rightPop(String cacheName, String key, Class<T> clazz);

    /**
     * List 批量左側推入
     */
    long leftPushAll(String cacheName, String key, List<Object> values);

    /**
     * List 批量右側推入
     */
    long rightPushAll(String cacheName, String key, List<Object> values);

    /**
     * List 獲取範圍內的元素
     */
    <T> List<T> getRange(String cacheName, String key, long start, long end, Class<T> clazz);

    /**
     * List 獲取大小
     */
    long getListSize(String cacheName, String key);

    // ==================== Set 操作 ====================

    /**
     * Set 添加元素
     */
    long setAdd(String cacheName, String key, Object... values);

    /**
     * Set 移除元素
     */
    long setRemove(String cacheName, String key, Object... values);

    /**
     * Set 檢查元素是否存在
     */
    boolean setIsUser(String cacheName, String key, Object value);

    /**
     * Set 獲取所有元素
     */
    <T> Set<T> setUsers(String cacheName, String key, Class<T> clazz);

    /**
     * Set 獲取大小
     */
    long setSize(String cacheName, String key);

    // ==================== 計數器操作 ====================

    /**
     * 遞增
     */
    long increment(String cacheName, String key);

    /**
     * 遞增指定值
     */
    long increment(String cacheName, String key, long delta);

    /**
     * 遞減
     */
    long decrement(String cacheName, String key);

    /**
     * 遞減指定值
     */
    long decrement(String cacheName, String key, long delta);

    // ==================== 模式匹配操作 ====================

    /**
     * 根據模式獲取鍵集合
     */
    Set<String> keys(String cacheName, String pattern);

    /**
     * 根據模式刪除鍵
     */
    long deleteByPattern(String cacheName, String pattern);
}
