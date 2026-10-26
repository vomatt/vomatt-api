package com.vomatt.common.security;

import com.vomatt.common.redis.RedisService;
import com.vomatt.entity.User;
import com.vomatt.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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

    // Every key and index field below is keyOf(token) = SHA-256(token), never the token itself: a Redis dump cannot
    // mint access tokens, and the client-held value alone is what the server never stores
    public static final String NAMESPACE = "refresh";
    // Consumed (rotated) token -> its session id, used for reuse detection; kept as long as the refresh token
    // itself could have lived, so a stolen token replayed at any point in its lifetime still trips the detector
    public static final String CONSUMED_NS = "refresh:consumed";
    // Sessions ended by reuse detection: their live token can no longer rotate
    public static final String REVOKED_SESSION_NS = "refresh:revoked-session";
    // User -> active token index, used to revoke all
    public static final String USER_IDX_NS = "refresh:idx";
    // In-flight race grace cache: the same oldToken returns the same pair within the TTL, so a cross-instance race, or a
    // retry after the response carrying the new pair was lost, does not trigger a reuse alert. A pair is handed out only
    // while its session stands (see graceHit), so the window cannot outlive a logout, a ban or a session revocation
    public static final String GRACE_NS = "refresh:grace";
    private static final int SHA_PREFIX_LEN = 8;

    @Value("${jwt.refresh-expiration-days:30}")
    private long refreshExpirationDays;

    @Value("${jwt.refresh-grace-seconds:60}")
    private long refreshGraceSeconds;

    /** Redis refuses a zero or negative expiry, so such a grace window would fail every refresh with 503. */
    @PostConstruct
    void checkGraceWindow() {
        if (refreshGraceSeconds < 1) {
            throw new IllegalStateException("JWT_REFRESH_GRACE_SECONDS must be at least 1, was " + refreshGraceSeconds);
        }
    }

    /**
     * {@code sessionId} names one sign-in and is kept across rotations; null on tokens issued before it existed.
     * {@code adminScope} is fixed at sign-in (admin console login only) and kept across rotations, so refreshing a
     * consumer-app session never yields an admin token; null on tokens issued before it existed (read as false).
     */
    public record RefreshTokenData(String userId, List<String> roles, String sessionId, Boolean adminScope) {
        public boolean grantsAdmin() {
            return Boolean.TRUE.equals(adminScope);
        }
    }

    public record RotateResult(String newAccessToken, String newRefreshToken, RefreshTokenData data) {}

    /**
     * Generates a refresh token, stores it in Redis and indexes it under the user
     */
    public String generate(String userId, List<String> roles, boolean adminScope) {
        String token = UUID.randomUUID().toString();
        String key = keyOf(token);
        RefreshTokenData data = new RefreshTokenData(userId, roles, UUID.randomUUID().toString(), adminScope);
        Duration ttl = Duration.ofDays(refreshExpirationDays);
        // Renew the index TTL on every issue (as rotate does), so the index never expires before a token it lists
        redisService.execInTransaction(List.of(
                RedisService.RedisWrite.set(NAMESPACE, key, data, ttl),
                RedisService.RedisWrite.hput(USER_IDX_NS, userId, key, "1"),
                RedisService.RedisWrite.expire(USER_IDX_NS, userId, ttl)
        ));
        log.debug("Refresh token generated for userId={}", userId);
        return token;
    }

    /**
     * Token rotation: revokes the old token and issues a new one.
     *
     * Flow (order must not change):
     *   1. Grace cache hit -> return the already issued pair (no reuse alert, no namespace writes), or null if its session
     *      has since been revoked
     *   2. Read the old token data without consuming it
     *   3. data == null -> a concurrent caller may have just committed, so check the grace cache again; then check
     *      CONSUMED_NS for reuse; on a hit, mark the token's session revoked and throw RefreshTokenReuseException
     *      (the user's other sessions are untouched)
     *   4. data != null -> generate a new pair
     *   5. One atomic step checks the session is not revoked (else drops the token and returns null), then consumes the
     *      old token and writes grace, consumed, the new token and the user index. Racing callers each get here; exactly
     *      one consumes the token, the others return the pair it wrote to grace.
     *      The token disappears only together with its grace pair, so a concurrent caller can never find neither
     *      (which used to answer 401 and sign the user out).
     *
     * Returns null if the token is invalid or expired (not reused).
     */
    public RotateResult rotate(String oldToken) {
        String oldKey = keyOf(oldToken);
        // Step 1: return on grace cache hit (must come before the reuse detector)
        RotateResult cached = graceHit(oldKey);
        if (cached != null) {
            return sessionStands(cached) ? cached : null;
        }

        // Step 2: throws on Redis failure, so an outage is a 503 rather than a sign-out
        RefreshTokenData data = redisService.getOrThrow(NAMESPACE, oldKey, RefreshTokenData.class);
        if (data == null) {
            // Step 3: a concurrent rotation may have committed since step 1
            cached = graceHit(oldKey);
            if (cached != null) {
                return sessionStands(cached) ? cached : null;
            }
            // Reuse detection; throws on Redis failure so a replay during an outage can't pass as merely invalid
            String sessionId = redisService.getOrThrow(CONSUMED_NS, oldKey, String.class);
            if (sessionId != null) {
                // Markers written before 2026-10-17 hold the userId instead of a session id: fall back to revoking all.
                // Remove after 2026-11-16, when the last of them has expired with its refresh lifetime.
                if (redisService.hasKeyOrThrow(USER_IDX_NS, sessionId)) {
                    log.warn("SECURITY_BREACH: Refresh token reuse detected (legacy marker), revoking all sessions for userId={}", sessionId);
                    revokeAllForUser(sessionId);
                } else {
                    // A flag rather than deleting the live token: it holds even if the session rotates at this very moment
                    redisService.setOrThrow(REVOKED_SESSION_NS, sessionId, "1", Duration.ofDays(refreshExpirationDays));
                    log.warn("SECURITY_BREACH: Refresh token reuse detected, revoked its session refreshToken_sha={}",
                            oldKey.substring(0, SHA_PREFIX_LEN));
                }
                throw new RefreshTokenReuseException("Refresh token reuse detected");
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
            // Explicitly too: revokeAllForUser only finds tokens listed in the user index
            redisService.delete(NAMESPACE, oldKey);
            revokeAllForUser(data.userId());
            return null;
        }

        // Use the latest roles from the DB so later rotations keep using current permissions
        List<String> freshRoles = List.of(user.getRoles());
        // Tokens issued before session ids existed start a session on their next rotation
        String sessionId = data.sessionId() != null ? data.sessionId() : UUID.randomUUID().toString();
        RefreshTokenData freshData = new RefreshTokenData(data.userId(), freshRoles, sessionId, data.grantsAdmin());

        String newRefreshToken = UUID.randomUUID().toString();
        String newKey = keyOf(newRefreshToken);
        String newAccessToken = jwtUtil.generateToken(freshData.userId(), freshRoles, freshData.grantsAdmin());
        RotateResult result = new RotateResult(newAccessToken, newRefreshToken, freshData);

        Duration refreshTtl = Duration.ofDays(refreshExpirationDays);
        Duration graceTtl = Duration.ofSeconds(refreshGraceSeconds);

        // Step 5: the revoked-session check runs inside the script, so reuse detection landing after step 2 still stops
        // this rotation. CONSUMED (reuse detection), NAMESPACE (the new token) and USER_IDX are written before GRACE
        // (where racing callers meet): if an earlier write fails, no caller is handed a pair whose token was never stored
        RedisService.RotationOutcome outcome = redisService.rotateRefreshToken(new RedisService.RefreshRotation(
                freshData.userId(), sessionId, oldKey, newKey, freshData, result, refreshTtl, graceTtl));
        if (outcome == RedisService.RotationOutcome.SESSION_REVOKED) {
            log.warn("Refresh rotation rejected (session revoked after reuse) userId={}", data.userId());
            return null;
        }
        if (outcome == RedisService.RotationOutcome.TOKEN_GONE) {
            // A concurrent caller consumed it first and wrote its pair to grace in the same step. Null only if the
            // token was revoked (logout) in the meantime.
            log.info("refresh.race_lost refreshToken_sha={}", oldKey.substring(0, SHA_PREFIX_LEN));
            cached = graceHit(oldKey);
            return cached != null && sessionStands(cached) ? cached : null;
        }

        log.debug("Refresh token rotated for userId={}", freshData.userId());
        return result;
    }

    /** Throws on Redis failure: a read error taken for a miss would answer 401 (or a reuse alert) to a live session. */
    private RotateResult graceHit(String oldKey) {
        RotateResult cached = redisService.getOrThrow(GRACE_NS, oldKey, RotateResult.class);
        if (cached != null) {
            log.info("refresh.cache_hit refreshToken_sha={}", oldKey.substring(0, SHA_PREFIX_LEN));
        }
        return cached;
    }

    /**
     * Whether the session a grace pair belongs to still stands. Rather than clearing grace entries on every revocation
     * path (they are keyed by the consumed token, which the user index no longer lists), each hit checks what those
     * paths leave behind: logout and revokeAllForUser (suspend, role change, delete) delete the pair's refresh token,
     * reuse detection flags the session, and a ban or deletion also shows in the DB. A refresh token that was rotated
     * again since leaves a consumed marker and still counts, so a late duplicate request is not mistaken for a revocation.
     * Throws on Redis failure, so an outage cannot hand out a revoked pair.
     */
    private boolean sessionStands(RotateResult pair) {
        String newKey = keyOf(pair.newRefreshToken());
        boolean stands = (redisService.hasKeyOrThrow(NAMESPACE, newKey) || redisService.hasKeyOrThrow(CONSUMED_NS, newKey))
                && !redisService.hasKeyOrThrow(REVOKED_SESSION_NS, pair.data().sessionId())
                && userRepository.findById(UUID.fromString(pair.data().userId())).filter(User::isActive).isPresent();
        if (!stands) {
            log.warn("refresh.grace_rejected (session revoked) userId={}", pair.data().userId());
        }
        return stands;
    }

    /**
     * Invalidates a refresh token (logout)
     */
    public void revoke(String token) {
        String key = keyOf(token);
        RefreshTokenData data = redisService.getAndDelete(NAMESPACE, key, RefreshTokenData.class);
        if (data != null) {
            redisService.hDelete(USER_IDX_NS, data.userId(), key);
        }
        log.debug("Refresh token revoked, found={}", data != null);
    }

    /**
     * Revokes all of a user's refresh tokens (security incident handling)
     */
    public void revokeAllForUser(String userId) {
        Map<String, Object> userTokens = redisService.hGetAll(USER_IDX_NS, userId);
        int count = 0;
        // Index fields are already keyOf(token)
        for (String key : userTokens.keySet()) {
            redisService.delete(NAMESPACE, key);
            count++;
        }
        redisService.delete(USER_IDX_NS, userId);
        log.warn("Revoked {} refresh tokens for userId={}", count, userId);
    }

    /** Redis key of a token: its SHA-256 hex. The same digest is what db/redis-hash-refresh-keys.sh wrote on migration. */
    static String keyOf(String token) {
        return AppKey.sha256Hex(token);
    }
}
