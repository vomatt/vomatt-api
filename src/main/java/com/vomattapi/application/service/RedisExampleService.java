package com.vomattapi.application.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.vomattapi.domain.member.Member;
import com.vomattapi.domain.member.repository.MemberRepository;
import com.vomattapi.domain.vote.Vote;
import com.vomattapi.domain.vote.repository.VoteRepository;
import com.vomattapi.infrastructure.redis.RedisService;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * Redis 使用示例服務
 * 展示各種 Redis 操作模式，包括批量操作和高性能序列化
 */
@Slf4j
@Service
public class RedisExampleService {

    @Autowired
    private RedisService redisService;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private VoteRepository voteRepository;

    // ==================== 用戶會話管理 ====================

    /**
     * 儲存用戶會話
     */
    public void storeUserSession(String sessionId, UserSession session) {
        redisService.set("user_session", sessionId, session, Duration.ofHours(24));
        log.info("Stored user session: sessionId={}, userId={}", sessionId, session.getUserId());
    }

    /**
     * 獲取用戶會話
     */
    public UserSession getUserSession(String sessionId) {
        return redisService.get("user_session", sessionId, UserSession.class);
    }

    /**
     * 批量儲存用戶會話
     */
    public void batchStoreUserSessions(Map<String, UserSession> sessions) {
        Map<String, Object> cacheData = sessions.entrySet().stream()
            .collect(Collectors.toMap(
                entry -> "session:" + entry.getKey(),
                Map.Entry::getValue
            ));

        Map<String, Object> sessionData = sessions.entrySet().stream()
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                Map.Entry::getValue
            ));
        redisService.multiSet("user_session", sessionData, Duration.ofHours(24));
        log.info("Batch stored {} user sessions", sessions.size());
    }

    /**
     * 批量獲取用戶會話
     */
    public Map<String, UserSession> batchGetUserSessions(Set<String> sessionIds) {
        return redisService.multiGet("user_session", sessionIds, UserSession.class);
    }

    // ==================== 用戶活躍度統計 ====================

    /**
     * 記錄用戶活躍度
     */
    public void recordUserActivity(String userId, String activity) {
        // 使用 Hash 儲存用戶的各種活動計數
        Long currentCount = redisService.hGet("user_activity", userId, activity, Long.class);
        redisService.hSet("user_activity", userId, activity, (currentCount != null ? currentCount : 0) + 1);

        // 設置過期時間為30天
        redisService.expire("user_activity", userId, Duration.ofDays(30));

        log.debug("Recorded activity: userId={}, activity={}", userId, activity);
    }

    /**
     * 批量記錄用戶活躍度
     */
    public void batchRecordUserActivity(Map<String, Map<String, Long>> userActivities) {
        userActivities.forEach((userId, activities) -> {
            Map<String, Object> activityData = new HashMap<>(activities);
            redisService.hMultiSet("user_activity", userId, activityData);
            redisService.expire("user_activity", userId, Duration.ofDays(30));
        });

        log.info("Batch recorded activities for {} users", userActivities.size());
    }

    /**
     * 獲取用戶活躍度統計
     */
    public Map<String, Long> getUserActivityStats(String userId) {
        return redisService.hGetAll("user_activity", userId, Long.class);
    }

    /**
     * 批量獲取用戶活躍度統計
     */
    public Map<String, Map<String, Long>> batchGetUserActivityStats(Set<String> userIds) {
        Map<String, Map<String, Long>> result = new HashMap<>();

        userIds.forEach(userId -> {
            Map<String, Long> stats = getUserActivityStats(userId);
            if (!stats.isEmpty()) {
                result.put(userId, stats);
            }
        });

        return result;
    }

    // ==================== 投票結果緩存 ====================

    /**
     * 緩存投票結果
     */
    public void cacheVoteResults(String voteId, VoteResult result) {
        redisService.set("vote_result", voteId, result, Duration.ofMinutes(30));
        log.debug("Cached vote results: voteId={}", voteId);
    }

    /**
     * 批量緩存投票結果
     */
    public void batchCacheVoteResults(Map<String, VoteResult> voteResults) {
        Map<String, Object> resultData = voteResults.entrySet().stream()
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                Map.Entry::getValue
            ));
        redisService.multiSet("vote_result", resultData, Duration.ofMinutes(30));
        log.info("Batch cached {} vote results", voteResults.size());
    }

    /**
     * 獲取投票結果
     */
    public VoteResult getVoteResults(String voteId) {
        VoteResult cached = redisService.get("vote_result", voteId, VoteResult.class);

        if (cached == null) {
            // 從資料庫查詢並緩存
            cached = calculateVoteResultsFromDB(voteId);
            if (cached != null) {
                cacheVoteResults(voteId, cached);
            }
        }

        return cached;
    }

    // ==================== 排行榜功能 ====================

    /**
     * 更新用戶分數排行榜
     */
    public void updateUserScore(String userId, double score) {
        redisService.setAdd("leaderboard", "users", new UserScore(userId, score));
        log.debug("Updated user score: userId={}, score={}", userId, score);
    }

    /**
     * 獲取排行榜前N名
     */
    public List<UserScore> getTopUsers(int limit) {
        Set<UserScore> allScores = redisService.setMembers("leaderboard", "users", UserScore.class);

        return allScores.stream()
            .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
            .limit(limit)
            .collect(Collectors.toList());
    }

    // ==================== 計數器功能 ====================

    /**
     * 遞增頁面瀏覽量
     */
    public long incrementPageViews(String pageId) {
        return redisService.increment("page_views", pageId);
    }

    /**
     * 批量遞增頁面瀏覽量
     */
    public void batchIncrementPageViews(Map<String, Long> pageViews) {
        pageViews.forEach((pageId, count) -> {
            redisService.increment("page_views", pageId, count);
        });
        log.info("Batch incremented page views for {} pages", pageViews.size());
    }

    /**
     * 獲取頁面瀏覽量
     */
    public long getPageViews(String pageId) {
        Long value = redisService.get("page_views", pageId, Long.class);
        return value != null ? value : 0;
    }

    // ==================== 緊急清理功能 ====================

    /**
     * 清理過期的會話
     */
    public long cleanupExpiredSessions() {
        Set<String> sessionKeys = redisService.keys("user_session", "*");

        long cleanedCount = 0;
        for (String key : sessionKeys) {
            Duration remaining = redisService.getExpire("user_session", key);
            if (remaining == null || remaining.isNegative()) {
                redisService.delete("user_session", key);
                cleanedCount++;
            }
        }

        log.info("Cleaned up {} expired sessions", cleanedCount);
        return cleanedCount;
    }

    /**
     * 清理特定用戶的所有緩存
     */
    public long cleanupUserCache(String userId) {
        long deleted = 0;
        // Clean up from different cache namespaces
        deleted += redisService.deleteByPattern("user_session", userId + "*");
        deleted += redisService.deleteByPattern("user_activity", userId + "*");
        deleted += redisService.deleteByPattern("page_views", userId + "*");
        log.info("Cleaned up {} cache entries for user: {}", deleted, userId);
        return deleted;
    }

    // ==================== 私有輔助方法 ====================

    private VoteResult calculateVoteResultsFromDB(String voteId) {
        // 這裡應該是實際的資料庫查詢邏輯
        // 簡化示例
        return new VoteResult(voteId, 100, Map.of("option1", 60L, "option2", 40L));
    }

    // ==================== 內部數據類 ====================

    @Data
    public static class UserSession {
        private String userId;
        private String username;
        private LocalDateTime loginTime;
        private String ipAddress;
        private String userAgent;

        public UserSession() {}

        public UserSession(String userId, String username, LocalDateTime loginTime,
                          String ipAddress, String userAgent) {
            this.userId = userId;
            this.username = username;
            this.loginTime = loginTime;
            this.ipAddress = ipAddress;
            this.userAgent = userAgent;
        }
    }

    @Data
    public static class VoteResult {
        private String voteId;
        private long totalVotes;
        private Map<String, Long> optionCounts;
        private LocalDateTime calculatedAt;

        public VoteResult() {}

        public VoteResult(String voteId, long totalVotes, Map<String, Long> optionCounts) {
            this.voteId = voteId;
            this.totalVotes = totalVotes;
            this.optionCounts = optionCounts;
            this.calculatedAt = LocalDateTime.now();
        }
    }

    @Data
    public static class UserScore {
        private String userId;
        private double score;
        private LocalDateTime updatedAt;

        public UserScore() {}

        public UserScore(String userId, double score) {
            this.userId = userId;
            this.score = score;
            this.updatedAt = LocalDateTime.now();
        }
    }
}
