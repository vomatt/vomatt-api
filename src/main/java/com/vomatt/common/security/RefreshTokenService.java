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
import java.util.HexFormat;
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
    // Consumed (rotated) tokens, used for reuse detection; kept as long as the refresh token itself could have lived,
    // so a stolen token replayed at any point in its lifetime still trips the detector
    private static final String CONSUMED_NS = "refresh:consumed";
    // User -> active token index, used to revoke all
    private static final String USER_IDX_NS = "refresh:idx";
    // In-flight race grace cache: the same oldToken returns the same pair within the TTL, so a cross-instance race does not trigger a reuse alert
    private static final String GRACE_NS = "refresh:grace";
    private static final int SHA_PREFIX_LEN = 8;

    @Value("${jwt.refresh-expiration-days:30}")
    private long refreshExpirationDays;

    @Value("${jwt.refresh-grace-seconds:10}")
    private long refreshGraceSeconds;

    public record RefreshTokenData(String userId, String email, List<String> roles) {}

    public record RotateResult(String newAccessToken, String newRefreshToken, RefreshTokenData data) {}

    /**
     * Generates a refresh token, stores it in Redis and indexes it under the user
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
     * Token rotation: revokes the old token and issues a new one.
     *
     * Flow (order must not change):
     *   1. Grace cache hit -> return the already issued pair (no reuse alert, no namespace writes)
     *   2. Atomic GETDEL to take the old token data
     *   3. data == null -> check CONSUMED_NS for reuse; on a hit, throw RefreshTokenReuseException and revoke all of the user's sessions
     *   4. data != null -> generate a new pair, write grace -> consumed -> namespace -> userIdx
     *
     * Returns null if the token is invalid or expired (not reused).
     */
    public RotateResult rotate(String oldToken) {
        // Step 1: return on grace cache hit (must come before the reuse detector)
        RotateResult cached = redisService.get(GRACE_NS, oldToken, RotateResult.class);
        if (cached != null) {
            log.info("refresh.cache_hit refreshToken_sha={}", sha256Prefix(oldToken, SHA_PREFIX_LEN));
            return cached;
        }

        // Step 2: atomic GETDEL so two instances cannot both get the data
        RefreshTokenData data = redisService.getAndDelete(NAMESPACE, oldToken, RefreshTokenData.class);
        if (data == null) {
            // Step 3: reuse detection
            String consumedUserId = redisService.get(CONSUMED_NS, oldToken, String.class);
            if (consumedUserId != null) {
                log.warn("SECURITY_BREACH: Refresh token reuse detected, revoking all sessions for userId={}", consumedUserId);
                revokeAllForUser(consumedUserId);
                throw new RefreshTokenReuseException("Refresh token reuse detected for userId=" + consumedUserId);
            }
            return null;
        }

        // Step 4: reload the profile before generating a new pair.
        // Do not trust the stale roles in the refresh token (they may have been revoked) or ignore the ban status; otherwise a user stripped of ADMIN /
        // a banned user could keep going for 30 days via rotation. Reload from the DB for the latest roles / isBanned.
        User user = userRepository.findById(UUID.fromString(data.userId())).orElse(null);
        if (user == null || !user.isActive()) {
            // Account missing or banned -> revoke all of the user's sessions and return null so the caller returns 401
            log.warn("Refresh rotation rejected (user missing or banned), revoking all sessions for userId={}",
                    data.userId());
            revokeAllForUser(data.userId());
            return null;
        }

        // Use the latest roles from the DB so later rotations keep using current permissions
        List<String> freshRoles = List.of(user.getRoles());
        RefreshTokenData freshData = new RefreshTokenData(data.userId(), data.email(), freshRoles);

        String newRefreshToken = UUID.randomUUID().toString();
        String newAccessToken = jwtUtil.generateToken(freshData.userId(), freshData.email(), freshRoles);
        RotateResult result = new RotateResult(newAccessToken, newRefreshToken, freshData);

        Duration refreshTtl = Duration.ofDays(refreshExpirationDays);
        Duration graceTtl = Duration.ofSeconds(refreshGraceSeconds);

        // Order: grace first (where both sides of a race meet) -> then atomically write the remaining correctness-critical items.
        // GRACE is an optimization for cross-instance races; if it fails the reuse detector takes over (fail-safe), so it stays fail-soft and is written separately.
        redisService.set(GRACE_NS, oldToken, result, graceTtl);

        // P1-#17: CONSUMED (reuse detection), NAMESPACE (the new token), USER_IDX (revocation index + TTL refresh + old token removal)
        // are correctness-critical and used to take one round-trip each. They are now applied atomically in a single MULTI/EXEC: one round-trip,
        // fully atomic (no intermediate "partially written then threw" state), and a connection failure throws for the whole batch (fail-fast, no weaker than the old per-write throwing).
        redisService.execInTransaction(List.of(
                RedisService.RedisWrite.set(CONSUMED_NS, oldToken, freshData.userId(), refreshTtl),
                RedisService.RedisWrite.set(NAMESPACE, newRefreshToken, freshData, refreshTtl),
                RedisService.RedisWrite.hput(USER_IDX_NS, freshData.userId(), newRefreshToken, "1"),
                RedisService.RedisWrite.expire(USER_IDX_NS, freshData.userId(), refreshTtl),
                RedisService.RedisWrite.hdelete(USER_IDX_NS, freshData.userId(), oldToken)
        ));

        log.debug("Refresh token rotated for userId={}", freshData.userId());
        return result;
    }

    /**
     * Invalidates a refresh token (logout)
     */
    public void revoke(String token) {
        RefreshTokenData data = redisService.getAndDelete(NAMESPACE, token, RefreshTokenData.class);
        if (data != null) {
            redisService.hDelete(USER_IDX_NS, data.userId(), token);
        }
        log.debug("Refresh token revoked, found={}", data != null);
    }

    /**
     * Revokes all of a user's refresh tokens (security incident handling)
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
     * Computes the first N characters of the token's SHA-256 hex digest, to identify it in logs without exposing the token
     */
    private String sha256Prefix(String token, int prefixLen) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, prefixLen);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed to exist in the JVM; this path should never happen
            return "unknown";
        }
    }
}
