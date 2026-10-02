package com.vomatt.common.security;

import com.vomatt.common.redis.RedisService;
import com.vomatt.entity.User;
import com.vomatt.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final RedisService redisService;
    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;

    private static final String NAMESPACE = "refresh";
    // 已消耗（旋轉過）的 token，用於 Reuse Detection；TTL 60 秒偵測視窗
    private static final String CONSUMED_NS = "refresh:consumed";
    // 用戶 → 活躍 token 索引，用於全部撤銷
    private static final String USER_IDX_NS = "refresh:idx";
    // In-flight race grace cache：同一 oldToken 於 TTL 內回傳同一組 pair，避免 cross-instance race 觸發 reuse 警報
    private static final String GRACE_NS = "refresh:grace";
    private static final Duration CONSUMED_TTL = Duration.ofSeconds(60);
    private static final int SHA_PREFIX_LEN = 8;

    @Value("${jwt.refresh-expiration-days:30}")
    private long refreshExpirationDays;

    @Value("${jwt.refresh-grace-seconds:10}")
    private long refreshGraceSeconds;

    public record RefreshTokenData(String userId, String email, List<String> roles) {}

    public record RotateResult(String newAccessToken, String newRefreshToken, RefreshTokenData data) {}

    /**
     * 產生 refresh token 並存入 Redis，同時建立用戶索引
     */
    public String generate(String userId, String email, List<String> roles) {
        String token = UUID.randomUUID().toString();
        RefreshTokenData data = new RefreshTokenData(userId, email, roles);
        Duration ttl = Duration.ofDays(refreshExpirationDays);
        redisService.setOrThrow(NAMESPACE, token, data, ttl);
        redisService.hSetOrThrow(USER_IDX_NS, userId, token, "1", ttl);
        log.debug("Refresh token generated for userId={}", userId);
        return token;
    }

    /**
     * Token Rotation：撤銷舊 token，發行新 token。
     *
     * 流程（順序不可變動）：
     *   1. Grace cache 命中 → 回傳已發行的同一組 pair（不觸發 reuse 警報、不寫任何 namespace）
     *   2. Atomic GETDEL 取出舊 token data
     *   3. data == null → 查 CONSUMED_NS 偵測 reuse；命中拋 RefreshTokenReuseException 並撤銷該用戶所有 session
     *   4. data != null → 生成新 pair，寫 grace → consumed → namespace → userIdx
     *
     * 回傳 null 代表 token 無效或已過期（非重複使用）。
     */
    public RotateResult rotate(String oldToken) {
        // Step 1：Grace cache 命中即回（必須在 reuse detector 之前）
        RotateResult cached = redisService.get(GRACE_NS, oldToken, RotateResult.class);
        if (cached != null) {
            log.info("refresh.cache_hit refreshToken_sha={}", sha256Prefix(oldToken, SHA_PREFIX_LEN));
            return cached;
        }

        // Step 2：Atomic GETDEL，避免雙 instance 同時拿到 data
        RefreshTokenData data = redisService.getAndDelete(NAMESPACE, oldToken, RefreshTokenData.class);
        if (data == null) {
            // Step 3：Reuse detection
            String consumedUserId = redisService.get(CONSUMED_NS, oldToken, String.class);
            if (consumedUserId != null) {
                log.warn("SECURITY_BREACH: Refresh token reuse detected, revoking all sessions for userId={}", consumedUserId);
                revokeAllForUser(consumedUserId);
                throw new RefreshTokenReuseException("Refresh token reuse detected for userId=" + consumedUserId);
            }
            return null;
        }

        // Step 4：重查 Profile 後再生成新 pair。
        // 不可信任 refresh token 內的舊 roles（可能被拔權）或忽略封禁狀態，否則被拔 ADMIN／
        // 被封禁者可靠 rotation 續命 30 天。重查 DB 取最新 roles / isBanned。
        User user = userRepository.findById(UUID.fromString(data.userId())).orElse(null);
        if (user == null || !user.isActive()) {
            // 帳號不存在或已封禁 → 撤銷該用戶所有 session，回 null 讓上層回 401
            log.warn("Refresh rotation rejected (user missing or banned), revoking all sessions for userId={}",
                    data.userId());
            revokeAllForUser(data.userId());
            return null;
        }

        // 以 DB 最新 roles 為準，確保後續 rotation 持續使用最新權限
        List<String> freshRoles = List.of(user.getRoles());
        RefreshTokenData freshData = new RefreshTokenData(data.userId(), data.email(), freshRoles);

        String newRefreshToken = UUID.randomUUID().toString();
        String newAccessToken = jwtUtil.generateToken(freshData.userId(), freshData.email(), freshRoles);
        RotateResult result = new RotateResult(newAccessToken, newRefreshToken, freshData);

        Duration refreshTtl = Duration.ofDays(refreshExpirationDays);
        Duration graceTtl = Duration.ofSeconds(refreshGraceSeconds);

        // 順序：先 grace（race 兩端碰面點）→ 再原子寫入其餘正確性關鍵項。
        // GRACE 為 cross-instance race 的最佳化，失敗時 reuse detector 會接手（fail-safe），故維持 fail-soft、單獨寫出。
        redisService.set(GRACE_NS, oldToken, result, graceTtl);

        // P1-#17：CONSUMED（reuse 偵測）、NAMESPACE（新 token 本體）、USER_IDX（撤銷索引＋刷新 TTL＋移除舊 token）
        // 為正確性關鍵，原本逐筆 round-trip。改以單一 MULTI/EXEC 原子套用：收斂為一次 round-trip，
        // 且整批原子（不再有「部分寫入後拋例外」的中間態），連線失敗整批拋出（fail-fast，語意不弱於原逐筆拋出型）。
        redisService.execInTransaction(List.of(
                RedisService.RedisWrite.set(CONSUMED_NS, oldToken, freshData.userId(), CONSUMED_TTL),
                RedisService.RedisWrite.set(NAMESPACE, newRefreshToken, freshData, refreshTtl),
                RedisService.RedisWrite.hput(USER_IDX_NS, freshData.userId(), newRefreshToken, "1"),
                RedisService.RedisWrite.expire(USER_IDX_NS, freshData.userId(), refreshTtl),
                RedisService.RedisWrite.hdelete(USER_IDX_NS, freshData.userId(), oldToken)
        ));

        log.debug("Refresh token rotated for userId={}", freshData.userId());
        return result;
    }

    /**
     * 使 refresh token 失效（logout）
     */
    public void revoke(String token) {
        RefreshTokenData data = redisService.get(NAMESPACE, token, RefreshTokenData.class);
        boolean deleted = redisService.delete(NAMESPACE, token);
        if (deleted && data != null) {
            redisService.hDelete(USER_IDX_NS, data.userId(), token);
        }
        log.debug("Refresh token revoked, found={}", deleted);
    }

    /**
     * 撤銷用戶所有 refresh token（安全事件處理）
     */
    public void revokeAllForUser(String userId) {
        Map<String, Object> userTokens = redisService.hGetAll(USER_IDX_NS, userId);
        int count = 0;
        for (String token : userTokens.keySet()) {
            redisService.delete(NAMESPACE, token);
            count++;
        }
        redisService.delete(USER_IDX_NS, userId);
        log.warn("Revoked {} refresh tokens for userId={}", count, userId);
    }

    /**
     * 計算 token SHA-256 hex digest 前 N 字元，用於 log 識別而不暴露原 token
     */
    private String sha256Prefix(String token, int prefixLen) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(prefixLen);
            for (int i = 0; i < digest.length && sb.length() < prefixLen; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.substring(0, Math.min(prefixLen, sb.length()));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 在 JVM 中保證存在；此路徑理論上不會發生
            return "unknown";
        }
    }
}
