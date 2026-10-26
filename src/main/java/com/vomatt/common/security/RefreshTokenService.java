package com.vomatt.common.security;

import com.vomatt.common.redis.CacheKeyUtil;
import com.vomatt.common.redis.RedisOperationException;
import com.vomatt.common.redis.RedisService;
import com.vomatt.entity.User;
import com.vomatt.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private static final String NAMESPACE = "refresh";
    // Consumed (rotated) token -> its session id, used for reuse detection; kept as long as the refresh token
    // itself could have lived, so a stolen token replayed at any point in its lifetime still trips the detector
    private static final String CONSUMED_NS = "refresh:consumed";
    // Revoked sessions (reuse detection, revokeAllForUser, logout with an already rotated token): their live token can no
    // longer rotate, the script drops it instead; kept as long as that token could live
    private static final String REVOKED_SESSION_NS = "refresh:revoked-session";
    // Ended sessions, for the grace window: no grace pair of theirs is handed out any more. Written by logout and by
    // every revocation, so it alone decides whether a grace pair is served
    private static final String ENDED_SESSION_NS = "refresh:ended-session";
    // User -> active token index (field keyOf(token), value its session id), used to revoke all
    private static final String USER_IDX_NS = "refresh:idx";
    // In-flight race grace cache: the same oldToken returns the same pair within the TTL, so a cross-instance race, or a
    // retry after the response carrying the new pair was lost, does not trigger a reuse alert. A pair is handed out only
    // while its session stands (see sessionStands), so the window cannot outlive a logout or a session revocation
    private static final String GRACE_NS = "refresh:grace";
    private static final int SHA_PREFIX_LEN = 8;

    // Step 5 of rotate. KEYS: 1 old token, 2 revoked-session flag, 3 consumed marker, 4 new token, 5 user index, 6 grace.
    // ARGV: 1 session id, 2 new token data, 3 grace pair, 4 new index field, 5 old index field, 6 refresh TTL ms,
    // 7 grace TTL ms. Returns 0 when the old token is gone, 2 when its session is revoked (the token is then dropped),
    // 1 when rotated. Redis keeps a failed script's earlier writes, so everything that could fail is checked before the
    // first write; grace is written after the token it hands out, and the old token goes last.
    private static final RedisScript<Long> ROTATE_LUA = RedisScript.of("""
            if redis.call('EXISTS', KEYS[1]) == 0 then return 0 end
            local idx = redis.call('TYPE', KEYS[5]).ok
            if redis.call('EXISTS', KEYS[2]) == 1 then
              if idx == 'hash' then redis.call('HDEL', KEYS[5], ARGV[5]) end
              redis.call('DEL', KEYS[1])
              return 2
            end
            local ttl, grace = tonumber(ARGV[6]), tonumber(ARGV[7])
            if (idx ~= 'hash' and idx ~= 'none') or not ttl or ttl <= 0 or not grace or grace <= 0 then
              return redis.error_reply('refresh rotation: bad user index type or TTL')
            end
            redis.call('HSET', KEYS[5], ARGV[4], ARGV[1])
            redis.call('PEXPIRE', KEYS[5], ARGV[6])
            redis.call('SET', KEYS[3], ARGV[1], 'PX', ARGV[6])
            redis.call('SET', KEYS[4], ARGV[2], 'PX', ARGV[6])
            redis.call('SET', KEYS[6], ARGV[3], 'PX', ARGV[7])
            redis.call('HDEL', KEYS[5], ARGV[5])
            redis.call('DEL', KEYS[1])
            return 1
            """, Long.class);

    @Value("${jwt.refresh-expiration-days:30}")
    private long refreshExpirationDays;

    @Value("${jwt.refresh-grace-seconds:10}")
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
                RedisService.RedisWrite.hput(USER_IDX_NS, userId, key, data.sessionId()),
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
     *      has since ended
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
                    endSessions(List.of(sessionId), true);
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
        // this rotation
        long outcome = redisService.execScript(ROTATE_LUA, List.of(
                CacheKeyUtil.buildKey(NAMESPACE, oldKey),
                CacheKeyUtil.buildKey(REVOKED_SESSION_NS, sessionId),
                CacheKeyUtil.buildKey(CONSUMED_NS, oldKey),
                CacheKeyUtil.buildKey(NAMESPACE, newKey),
                CacheKeyUtil.buildKey(USER_IDX_NS, freshData.userId()),
                CacheKeyUtil.buildKey(GRACE_NS, oldKey)
        ), List.of(sessionId, freshData, result, raw(newKey), raw(oldKey),
                raw(Long.toString(refreshTtl.toMillis())), raw(Long.toString(graceTtl.toMillis()))));
        if (outcome == 2) {
            log.warn("Refresh rotation rejected (session revoked) userId={}", data.userId());
            return null;
        }
        if (outcome == 0) {
            // A concurrent caller consumed it first and wrote its pair to grace in the same step. Null only if the
            // token was revoked (logout) in the meantime.
            log.info("refresh.race_lost refreshToken_sha={}", oldKey.substring(0, SHA_PREFIX_LEN));
            cached = graceHit(oldKey);
            return cached != null && sessionStands(cached) ? cached : null;
        }
        if (outcome != 1) {
            throw new RedisOperationException("Unexpected refresh rotation result " + outcome, null);
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
     * Whether the session a grace pair belongs to still stands, i.e. was not ended by logout, revocation (reuse
     * detection, suspend, role change, delete) or a logout with an already rotated token. Throws on Redis failure, so an
     * outage cannot hand out a revoked pair.
     */
    private boolean sessionStands(RotateResult pair) {
        if (!redisService.hasKeyOrThrow(ENDED_SESSION_NS, pair.data().sessionId())) {
            return true;
        }
        log.warn("refresh.grace_rejected (session ended) userId={}", pair.data().userId());
        return false;
    }

    private static byte[] raw(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Ends sessions: no grace pair of theirs is handed out again, and with {@code revoke} their live token can no longer
     * rotate either. One transaction, so a revoked session never keeps its grace pairs.
     */
    private void endSessions(Collection<String> sessionIds, boolean revoke) {
        List<RedisService.RedisWrite> writes = new ArrayList<>();
        for (String sessionId : sessionIds) {
            writes.add(RedisService.RedisWrite.set(ENDED_SESSION_NS, sessionId, "1", Duration.ofSeconds(refreshGraceSeconds)));
            if (revoke) {
                writes.add(RedisService.RedisWrite.set(REVOKED_SESSION_NS, sessionId, "1", Duration.ofDays(refreshExpirationDays)));
            }
        }
        redisService.execInTransaction(writes);
    }

    /** The session of a token, live or already rotated (then from its consumed marker); null if neither is left. */
    private String sessionOf(String key) {
        RefreshTokenData data = redisService.getOrThrow(NAMESPACE, key, RefreshTokenData.class);
        return data != null ? data.sessionId() : redisService.getOrThrow(CONSUMED_NS, key, String.class);
    }

    /**
     * Invalidates a refresh token (logout). An already rotated token revokes its session: the successor it left behind
     * is not reachable from here (only the session id is known, not the user), so it is stopped from rotating instead.
     */
    public void revoke(String token) {
        String key = keyOf(token);
        RefreshTokenData data = redisService.getAndDelete(NAMESPACE, key, RefreshTokenData.class);
        if (data != null) {
            redisService.hDelete(USER_IDX_NS, data.userId(), key);
            if (data.sessionId() != null) {
                endSessions(List.of(data.sessionId()), false);
            }
        } else {
            String sessionId = redisService.getOrThrow(CONSUMED_NS, key, String.class);
            if (sessionId != null) {
                endSessions(List.of(sessionId), true);
            }
        }
        log.debug("Refresh token revoked, found={}", data != null);
    }

    /**
     * Revokes all of a user's refresh tokens (security incident handling)
     */
    public void revokeAllForUser(String userId) {
        // Throws on Redis failure: an empty answer would revoke nothing, silently
        Map<String, Object> index = redisService.hGetAllOrThrow(USER_IDX_NS, userId);
        if (index.isEmpty()) {
            return;
        }
        // Values are session ids, except "1" on entries written before they were stored there: read those from the token
        Set<String> sessionIds = new HashSet<>();
        index.forEach((key, value) -> {
            String sessionId = value instanceof String v && !"1".equals(v) ? v : sessionOf(key);
            if (sessionId != null) {
                sessionIds.add(sessionId);
            }
        });
        // Revoked before the tokens go, so a rotation of one of them running now is refused by the script, and a
        // successor it already wrote (not in this snapshot) belongs to a revoked session
        endSessions(sessionIds, true);
        // Index fields are already keyOf(token). Only the snapshot's fields go: a whole-key DEL would also drop an entry
        // written after the snapshot
        redisService.deleteAll(NAMESPACE, index.keySet());
        redisService.hDelete(USER_IDX_NS, userId, index.keySet().toArray(String[]::new));
        log.warn("Revoked {} refresh tokens for userId={}", index.size(), userId);
    }

    /** Redis key of a token: its SHA-256 hex. The same digest is what db/redis-hash-refresh-keys.sh wrote on migration. */
    static String keyOf(String token) {
        return AppKey.sha256Hex(token);
    }
}
