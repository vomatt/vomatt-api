package com.vomatt.auth;

import com.vomatt.auth.dto.AuthResponse;
import com.vomatt.common.constant.ReservedUsernames;
import com.vomatt.common.email.EmailService;
import com.vomatt.common.exception.ApiException;
import com.vomatt.common.redis.RedisService;
import com.vomatt.common.security.JwtUtil;
import com.vomatt.common.security.RefreshTokenService;
import com.vomatt.common.sms.SmsService;
import com.vomatt.entity.User;
import com.vomatt.groups.GroupService;
import com.vomatt.repository.UserRepository;
import com.vomatt.users.BotAvatar;
import com.vomatt.votes.VoteService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import com.vomatt.common.constant.LoginUnlock;
import com.vomatt.common.i18n.MessageKey;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final SecureRandom RANDOM = new SecureRandom();

    // P6-#21: Jackson 3 JsonMapper/ObjectMapper is thread-safe and should be reused as a singleton
    // (each build recreates the serializer/deserializer caches, which is wasteful). Apple login
    // shares this singleton for parsing the JWT header / payload.
    private static final tools.jackson.databind.json.JsonMapper JSON_MAPPER =
            tools.jackson.databind.json.JsonMapper.builder().build();

    // P6-#21: short-TTL in-memory cache for the Apple / Google JWKS, so each login does not hit the JWKS endpoint.
    // Self-contained in AuthService (no Redis); one holder + timestamp per JWKS URL. With mostly
    // reads and rare writes, an occasional duplicate fetch is acceptable (no lock needed).
    private static final Duration JWKS_CACHE_TTL = Duration.ofMinutes(10);
    // A kid miss refetches the JWKS (the provider may have rotated keys), but at most once per minute per URL,
    // so forged kids cannot turn every login attempt into an outbound fetch
    private static final Duration JWKS_REFETCH_COOLDOWN = Duration.ofMinutes(1);
    // Tolerated clock drift between us and the provider when checking id_token exp
    private static final Duration ID_TOKEN_CLOCK_SKEW = Duration.ofSeconds(60);

    private static final java.util.Set<String> GOOGLE_ISSUERS = java.util.Set.of("accounts.google.com", "https://accounts.google.com");

    /** JWKS cache holder: keys is the parsed public key list, fetchedAt is the fetch time. */
    private record JwksCache(List<java.util.Map<String, Object>> keys, Instant fetchedAt) {
        boolean isYoungerThan(Duration age) {
            return Duration.between(fetchedAt, Instant.now()).compareTo(age) < 0;
        }
    }

    private final java.util.Map<String, JwksCache> jwksCache = new java.util.concurrent.ConcurrentHashMap<>();

    private final UserRepository userRepository;
    private final VoteService voteService;
    private final GroupService groupService;
    private final TransactionTemplate transactionTemplate;
    private final RedisService redisService;
    private final EmailService emailService;
    private final SmsService smsService;
    private final JwtUtil jwtUtil;
    private final RestClient restClient;
    private final RefreshTokenService refreshTokenService;

    @Value("${google.client-id:}")
    private String googleClientId;

    @Value("${oauth.google.jwks-url}")
    private String googleJwksUrl;

    @Value("${line.channel-id:}")
    private String lineChannelId;

    @Value("${line.channel-secret:}")
    private String lineChannelSecret;

    @Value("${oauth.line.token-url}")
    private String lineTokenUrl;

    @Value("${oauth.line.verify-url}")
    private String lineVerifyUrl;

    @Value("${oauth.line.profile-url}")
    private String lineProfileUrl;

    @Value("${apple.client-id:}")
    private String appleClientId;

    @Value("${oauth.apple.jwks-url}")
    private String appleJwksUrl;

    // ── OTP ──────────────────────────────────────────────────────────

    private static final String OTP_EMAIL_NS = "otp:email";
    private static final String OTP_PHONE_NS = "otp:phone";
    private static final String OTP_ADMIN_EMAIL_NS = "otp:admin-email";

    @Value("${otp.ttl-seconds:180}")
    private long otpTtlSeconds;

    @Value("${otp.resend-cooldown-seconds:180}")
    private long otpResendCooldownSeconds;

    public void sendOtp(String email, String phone, String type, String clientIp) {
        String otpType = (type != null) ? type : "login";
        String code = generateOtpCode();

        if (email != null && !email.isBlank()) {
            sendEmailOtp(OTP_EMAIL_NS, User.normalizeEmail(email), code, clientIp);
        } else if (phone != null && !phone.isBlank()) {
            sendPhoneOtp(User.normalizePhone(phone), otpType, code, clientIp);
        } else {
            throw ApiException.badRequest(MessageKey.AUTH_IDENTIFIER_REQUIRED);
        }
    }

    /**
     * Admin console login, step 1. Its OTPs, attempt counters, lockouts and cooldowns live in their own namespace, so a
     * code sent by the consumer login cannot sign in here, and lockouts on one side do not block the other.
     */
    public void sendAdminOtp(String email, String clientIp) {
        String normalizedEmail = requireEmail(email);
        requireExistingAdminByEmail(normalizedEmail);
        sendEmailOtp(OTP_ADMIN_EMAIL_NS, normalizedEmail, generateOtpCode(), clientIp);
    }

    private void sendEmailOtp(String namespace, String normalizedEmail, String code, String clientIp) {
        String attemptKey = attemptKey(normalizedEmail, clientIp);
        rejectIfLocked(namespace, attemptKey);

        boolean isExisting = userRepository.existsByEmail(normalizedEmail);
        String otpType = isExisting ? "login" : "signup";

        acquireResendCooldown(namespace, normalizedEmail);
        storeOtp(namespace, normalizedEmail, attemptKey, new OtpRecord(code, otpType, Instant.now(), 0));

        try {
            if ("signup".equals(otpType)) {
                emailService.sendPreSignupEmail(normalizedEmail, code);
            } else {
                emailService.sendVerificationEmail(normalizedEmail, code);
            }
        } catch (Exception ex) {
            // The code never reached the user: drop it and shorten the cooldown so a retry is possible soon
            releaseUndeliveredOtp(namespace, normalizedEmail);
            log.warn("Failed to send OTP email: {}", ex.getMessage());
            throw ApiException.badRequest(MessageKey.AUTH_EMAIL_SEND_FAILED);
        }
    }

    private void sendPhoneOtp(String phone, String otpType, String code, String clientIp) {
        String attemptKey = attemptKey(phone, clientIp);
        rejectIfLocked(OTP_PHONE_NS, attemptKey);

        if ("login".equals(otpType) && !userRepository.existsByPhoneNumber(phone)) {
            throw ApiException.badRequest(MessageKey.AUTH_PHONE_NOT_REGISTERED);
        }

        acquireResendCooldown(OTP_PHONE_NS, phone);
        storeOtp(OTP_PHONE_NS, phone, attemptKey, new OtpRecord(code, otpType, Instant.now(), 0));

        try {
            smsService.sendOtp(phone, code);
        } catch (Exception ex) {
            releaseUndeliveredOtp(OTP_PHONE_NS, phone);
            log.warn("Failed to send OTP SMS: {}", ex.getMessage());
            throw ApiException.badRequest(MessageKey.AUTH_SMS_SEND_FAILED);
        }
    }

    public AuthResponse verifyOtp(String email, String phone, String code, String clientIp) {
        if (email != null && !email.isBlank()) {
            return verifyEmailOtp(User.normalizeEmail(email), code, clientIp);
        } else if (phone != null && !phone.isBlank()) {
            return verifyPhoneOtp(User.normalizePhone(phone), code, clientIp);
        } else {
            throw ApiException.badRequest(MessageKey.AUTH_IDENTIFIER_REQUIRED);
        }
    }

    /** Admin console login, step 2: the only path that issues admin-scope tokens. Never creates an account. */
    public AuthResponse verifyAdminOtp(String email, String code, String clientIp) {
        String normalizedEmail = requireEmail(email);
        // P5-#13: the role check runs before the OTP is consumed, so a non-admin attempt burns nothing
        User user = requireExistingAdminByEmail(normalizedEmail);
        consumeOtp(OTP_ADMIN_EMAIL_NS, normalizedEmail, code, clientIp);
        return buildAuthResponse(user, true);
    }

    private static String requireEmail(String email) {
        if (email == null || email.isBlank()) {
            throw ApiException.badRequest(MessageKey.AUTH_IDENTIFIER_REQUIRED);
        }
        return User.normalizeEmail(email);
    }

    /** Requires an existing account for the email with the ADMIN role; otherwise throws 403. */
    private User requireExistingAdminByEmail(String normalizedEmail) {
        return userRepository.findByEmail(normalizedEmail)
                .filter(User::isAdmin)
                .orElseThrow(() -> ApiException.forbidden(MessageKey.AUTH_ADMIN_FORBIDDEN));
    }

    private AuthResponse verifyEmailOtp(String normalizedEmail, String code, String clientIp) {
        OtpRecord otp = consumeOtp(OTP_EMAIL_NS, normalizedEmail, code, clientIp);

        String defaultName = "signup".equals(otp.type()) ? normalizedEmail.split("@")[0] : "";
        // The user proved ownership of the email via OTP -> treat as verified
        User user = findOrCreateUserByEmail(normalizedEmail, defaultName, "email_otp", true);

        AuthResponse response = buildAuthResponse(user);
        if ("signup".equals(otp.type())) {
            // Sent only after the login succeeded, in the background so SMTP latency does not delay the response
            emailService.sendWelcomeEmail(normalizedEmail, user.getDisplayName(), LocaleContextHolder.getLocale());
        }
        return response;
    }

    private AuthResponse verifyPhoneOtp(String phone, String code, String clientIp) {
        consumeOtp(OTP_PHONE_NS, phone, code, clientIp);
        User user = findOrCreateUserByPhone(phone);
        return buildAuthResponse(user);
    }

    private static final int MAX_OTP_ATTEMPTS = 3;

    /** Guesses one OTP allows across all IPs, so more IPs buy no more guesses; past it the OTP no longer verifies. */
    private static final int MAX_OTP_GUESSES = 10;

    /**
     * Resend cooldown once an OTP's guesses are used up, so guessing across many IPs is held to about the old
     * per-identifier rate (10 guesses per 15 minutes). Anyone can impose it on a victim, like the old lockout could.
     */
    private static final Duration OTP_EXHAUSTED_COOLDOWN = Duration.ofMinutes(15);

    /** Namespace suffix for the per-OTP guess counter (all IPs), keyed by identifier + OTP creation time so each OTP starts at zero. */
    private static final String OTP_GUESS_SUFFIX = ":guesses";

    /**
     * Namespace suffix for the failure counter, atomic INCR (P1-#7). The counter, lock and level keys are all keyed by
     * {@link #attemptKey} (identifier + client IP), so guessing from one IP never locks the identifier out elsewhere.
     */
    private static final String OTP_FAIL_SUFFIX = ":fail";

    /** Namespace suffix for the lockout marker; independent of the OTP so it survives OTP deletion and resends. */
    private static final String OTP_LOCK_SUFFIX = ":lock";

    /** Namespace suffix for the lockout level: how many lockouts in a row, so each one doubles. */
    private static final String OTP_LEVEL_SUFFIX = ":level";

    /** Namespace suffix for the resend cooldown marker (SET NX), holding the epoch second a resend is allowed again. */
    private static final String OTP_COOLDOWN_SUFFIX = ":cooldown";

    private static final Duration OTP_LOCK_BASE = Duration.ofMinutes(5);
    // ponytail: lockout capped at 24h (5 min doubling reaches it at the 10th lockout); a longer ban needs an admin action
    private static final Duration OTP_LOCK_MAX = Duration.ofHours(24);
    /** How long the lockout level outlives the lock it set, so the next lockout within that window doubles again. */
    private static final Duration OTP_LEVEL_TTL = Duration.ofHours(24);

    /**
     * Namespace suffix for the per-identifier index of attempt keys that guessed wrong (hash: attemptKey -> "1"), so an
     * admin can clear one identifier's counters and locks across every IP; the attempt keys alone are one-way hashes.
     */
    private static final String OTP_ATTEMPT_IDX_SUFFIX = ":attempt-idx";
    /** Outlives the longest lock plus its level, so the index never expires before a key it lists. */
    private static final Duration OTP_ATTEMPT_IDX_TTL = OTP_LOCK_MAX.plus(OTP_LEVEL_TTL);

    /** Cooldown kept after a failed delivery, so a script cannot hammer the paid SMS / email provider. */
    private static final Duration OTP_FAILED_SEND_COOLDOWN = Duration.ofSeconds(30);

    private OtpRecord consumeOtp(String namespace, String key, String code, String clientIp) {
        String attemptKey = attemptKey(key, clientIp);
        rejectIfLocked(namespace, attemptKey);

        OtpRecord rec = redisService.get(namespace, key, OtpRecord.class);

        if (rec == null) {
            throw ApiException.unauthorized(MessageKey.AUTH_OTP_INVALID_OR_EXPIRED);
        }

        // Every attempt is counted with an atomic INCR BEFORE the code is compared, so concurrent guesses cannot
        // all slip past the limit (P1-#7). TTL matches the OTP's remaining lifetime (at least 1 second); a resend extends it.
        String failNs = namespace + OTP_FAIL_SUFFIX;
        long remainingSeconds = otpTtlSeconds - Duration.between(rec.createdAt(), Instant.now()).getSeconds();
        Duration counterTtl = Duration.ofSeconds(Math.max(remainingSeconds, 1));
        long attempts = redisService.incrementAndExpire(failNs, attemptKey, counterTtl);
        // Past the limit: a concurrent guess already used the last attempt (and locks out), so even a correct code is refused
        if (attempts > MAX_OTP_ATTEMPTS) {
            throw ApiException.unauthorized(MessageKey.AUTH_OTP_TOO_MANY_ATTEMPTS);
        }
        long guesses = redisService.incrementAndExpire(namespace + OTP_GUESS_SUFFIX,
                key + ":" + rec.createdAt().toEpochMilli(), counterTtl);
        // The counter belongs to this OTP only, so refusing here never touches an OTP resent in the meantime
        if (guesses > MAX_OTP_GUESSES) {
            throw ApiException.unauthorized(MessageKey.AUTH_OTP_INVALID_OR_EXPIRED);
        }

        // P5-#19: constant-time comparison so a short-circuit does not leak the OTP prefix via a timing side-channel.
        // MessageDigest.isEqual compares every byte without early exit when lengths match; it exits early on a length mismatch, but the OTP is always 6 digits.
        boolean codeMatches = MessageDigest.isEqual(
                rec.code().getBytes(StandardCharsets.UTF_8),
                code.getBytes(StandardCharsets.UTF_8));
        if (!codeMatches) {
            // The OTP's last guess was wrong: it is dead, and the next one can be requested only after the longer cooldown
            if (guesses == MAX_OTP_GUESSES) {
                redisService.set(namespace + OTP_COOLDOWN_SUFFIX, key, availableAt(OTP_EXHAUSTED_COOLDOWN), OTP_EXHAUSTED_COOLDOWN);
            }
            indexAttempt(namespace, key, attemptKey);
            // The last allowed attempt was wrong: lock now rather than on the next request.
            // Only this request escalates, so concurrent guesses cannot double the lockout twice.
            if (attempts == MAX_OTP_ATTEMPTS) {
                lockOut(namespace, attemptKey);
            }
            throw ApiException.unauthorized(MessageKey.AUTH_INVALID_OTP);
        }

        // Atomic GETDEL: of concurrent requests carrying the right code, only one consumes the OTP
        OtpRecord consumed = redisService.getAndDelete(namespace, key, OtpRecord.class);
        if (consumed == null) {
            throw ApiException.unauthorized(MessageKey.AUTH_OTP_INVALID_OR_EXPIRED);
        }
        if (!consumed.createdAt().equals(rec.createdAt())) {
            // A resend replaced the OTP after it was read: the old code must not sign in, and the new OTP goes back
            long left = otpTtlSeconds - Duration.between(consumed.createdAt(), Instant.now()).getSeconds();
            if (left > 0) {
                redisService.set(namespace, key, consumed, Duration.ofSeconds(left));
            }
            throw ApiException.unauthorized(MessageKey.AUTH_OTP_INVALID_OR_EXPIRED);
        }
        redisService.delete(failNs, attemptKey);
        redisService.delete(namespace + OTP_LEVEL_SUFFIX, attemptKey);
        return rec;
    }

    /**
     * Locks this identifier + IP for 5 minutes, doubling with each lockout in a row, then rejects. The OTP itself is kept,
     * so one IP's lockout does not void the code the real user is about to enter from another (only the shared
     * {@link #MAX_OTP_GUESSES} budget does). The counter is kept, so requests already in flight see it past the limit
     * and cannot escalate again; it outlives the OTP by at most its TTL, which is shorter than any lock, and a locked
     * pair cannot resend to extend it, so the next lock window starts with fresh attempts.
     */
    private void lockOut(String namespace, String attemptKey) {
        String levelNs = namespace + OTP_LEVEL_SUFFIX;
        long level = redisService.incrementAndExpire(levelNs, attemptKey, OTP_LOCK_BASE.plus(OTP_LEVEL_TTL));
        Duration lock = lockDuration(level);
        redisService.setOrThrow(namespace + OTP_LOCK_SUFFIX, attemptKey, "1", lock);
        redisService.expire(levelNs, attemptKey, lock.plus(OTP_LEVEL_TTL));
        throw ApiException.unauthorized(MessageKey.AUTH_OTP_TOO_MANY_ATTEMPTS);
    }

    /** 5 min for the first lockout, doubling each time: 5, 10, 20, 40 min... up to {@link #OTP_LOCK_MAX}. */
    static Duration lockDuration(long level) {
        // Clamp the exponent first: 5 min x 2^9 already exceeds 24h, and a larger shift would overflow
        Duration lock = OTP_LOCK_BASE.multipliedBy(1L << Math.clamp(level - 1, 0, 9));
        return lock.compareTo(OTP_LOCK_MAX) > 0 ? OTP_LOCK_MAX : lock;
    }

    /** Fail-soft: the index only serves admin unlock, so a Redis error must not turn a wrong-code 401 into a 500. */
    private void indexAttempt(String namespace, String identifier, String attemptKey) {
        try {
            redisService.execInTransaction(List.of(
                    RedisService.RedisWrite.hput(namespace + OTP_ATTEMPT_IDX_SUFFIX, identifier, attemptKey, "1"),
                    RedisService.RedisWrite.expire(namespace + OTP_ATTEMPT_IDX_SUFFIX, identifier, OTP_ATTEMPT_IDX_TTL)));
        } catch (RuntimeException ex) {
            log.warn("Failed to index OTP attempt: {}", ex.getMessage());
        }
    }

    /**
     * Admin unlock: clears the chosen {@link LoginUnlock} targets for one identifier on one channel.
     *
     * @param identifier normalized email or E.164 phone, as stored on the account
     * @param clientIp   only this IP (as the server resolved it); null for every IP that guessed wrong since the
     *                   index existed
     * @return how many Redis keys were removed (0 when nothing was locked or counted)
     */
    public int unlockLogin(String channel, String identifier, String clientIp, Set<String> targets) {
        String namespace = switch (channel) {
            case LoginUnlock.CHANNEL_EMAIL -> OTP_EMAIL_NS;
            case LoginUnlock.CHANNEL_PHONE -> OTP_PHONE_NS;
            case LoginUnlock.CHANNEL_ADMIN -> OTP_ADMIN_EMAIL_NS;
            default -> throw new IllegalArgumentException("Unknown login channel: " + channel);
        };
        String indexNs = namespace + OTP_ATTEMPT_IDX_SUFFIX;
        Collection<String> attemptKeys = clientIp != null ? List.of(attemptKey(identifier, clientIp))
                : redisService.hGetAll(indexNs, identifier).keySet();
        boolean lock = targets.contains(LoginUnlock.TARGET_LOCK);
        boolean attempts = targets.contains(LoginUnlock.TARGET_ATTEMPTS);
        int removed = 0;
        for (String attemptKey : attemptKeys) {
            if (lock) {
                removed += deleted(namespace + OTP_LOCK_SUFFIX, attemptKey) + deleted(namespace + OTP_LEVEL_SUFFIX, attemptKey);
            }
            if (attempts) {
                removed += deleted(namespace + OTP_FAIL_SUFFIX, attemptKey);
            }
        }
        if (attempts) {
            OtpRecord otp = redisService.get(namespace, identifier, OtpRecord.class);
            if (otp != null) {
                removed += deleted(namespace + OTP_GUESS_SUFFIX, identifier + ":" + otp.createdAt().toEpochMilli());
            }
        }
        if (targets.contains(LoginUnlock.TARGET_COOLDOWN)) {
            removed += deleted(namespace + OTP_COOLDOWN_SUFFIX, identifier);
        }
        // Nothing left to clear for these keys once both the lock and the counter are gone
        if (lock && attempts) {
            if (clientIp == null) {
                redisService.delete(indexNs, identifier);
            } else {
                redisService.hDelete(indexNs, identifier, attemptKeys.iterator().next());
            }
        }
        return removed;
    }

    private int deleted(String namespace, String key) {
        return redisService.delete(namespace, key) ? 1 : 0;
    }

    private void rejectIfLocked(String namespace, String attemptKey) {
        // Fails closed: a Redis error must not let a locked identifier guess again
        if (redisService.hasKeyOrThrow(namespace + OTP_LOCK_SUFFIX, attemptKey)) {
            throw ApiException.unauthorized(MessageKey.AUTH_OTP_TOO_MANY_ATTEMPTS);
        }
    }

    /** Redis key for one identifier guessing from one client IP; hashed so neither part can break the key format. */
    static String attemptKey(String identifier, String clientIp) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((identifier + "\n" + clientIp).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** Atomically claims the resend slot (SET NX + TTL), so concurrent sends cannot both pass the cooldown. */
    private void acquireResendCooldown(String namespace, String key) {
        String cooldownNs = namespace + OTP_COOLDOWN_SUFFIX;
        Duration cooldown = Duration.ofSeconds(otpResendCooldownSeconds);
        if (redisService.setIfAbsent(cooldownNs, key, availableAt(cooldown), cooldown)) {
            return;
        }
        String availableAt = redisService.get(cooldownNs, key, String.class);
        long remaining = availableAt == null ? otpResendCooldownSeconds : Long.parseLong(availableAt) - Instant.now().getEpochSecond();
        remaining = Math.max(remaining, 1);
        throw ApiException.badRequest(MessageKey.AUTH_OTP_RESEND_COOLDOWN, remaining);
    }

    /**
     * Stores the OTP and, in the same transaction, extends this identifier + IP's failure counter to outlive it, so a
     * resend never grants fresh guesses (the counter is not reset). If that fails, releases the cooldown just claimed.
     */
    private void storeOtp(String namespace, String key, String attemptKey, OtpRecord record) {
        Duration ttl = Duration.ofSeconds(otpTtlSeconds);
        try {
            redisService.execInTransaction(List.of(
                    RedisService.RedisWrite.set(namespace, key, record, ttl),
                    RedisService.RedisWrite.expire(namespace + OTP_FAIL_SUFFIX, attemptKey, ttl)));
        } catch (RuntimeException ex) {
            redisService.delete(namespace + OTP_COOLDOWN_SUFFIX, key);
            throw ex;
        }
    }

    /**
     * Drops an OTP whose delivery failed and shortens its cooldown to {@link #OTP_FAILED_SEND_COOLDOWN}, so the user can
     * retry soon but a failing provider is not hit on every request. Fail-soft: a Redis error must not mask the send failure.
     */
    private void releaseUndeliveredOtp(String namespace, String key) {
        redisService.delete(namespace, key);
        redisService.set(namespace + OTP_COOLDOWN_SUFFIX, key, availableAt(OTP_FAILED_SEND_COOLDOWN), OTP_FAILED_SEND_COOLDOWN);
    }

    private static String availableAt(Duration cooldown) {
        return Long.toString(Instant.now().plus(cooldown).getEpochSecond());
    }

    public boolean checkEmailExists(String email) {
        return userRepository.existsByEmail(User.normalizeEmail(email));
    }

    // ── Google OAuth ──────────────────────────────────────────────────

    public AuthResponse googleLogin(String idToken) {
        if (googleClientId == null || googleClientId.isBlank()) {
            throw ApiException.badRequest(MessageKey.AUTH_GOOGLE_NOT_CONFIGURED);
        }
        // JWKS outage -> 503 (thrown before the try, so it is not reported as an invalid token)
        List<java.util.Map<String, Object>> keys = fetchJwks(googleJwksUrl, "Google", false);

        String email;
        String name;
        boolean emailVerified;
        try {
            // Every local verification failure maps to auth.google.no_email ("token invalid"), except a wrong audience
            java.util.function.Supplier<ApiException> invalid = () -> ApiException.unauthorized(MessageKey.AUTH_GOOGLE_NO_EMAIL);
            var payload = verifyRs256(idToken, keys, googleJwksUrl, "Google", invalid, invalid, invalid);
            if (!GOOGLE_ISSUERS.contains(payload.get("iss"))) {
                throw invalid.get();
            }
            // google.client-id may list several client ids (web / iOS / Android), comma-separated
            boolean audOk = java.util.Arrays.stream(googleClientId.split(","))
                    .map(String::trim).anyMatch(id -> id.equals(payload.get("aud")));
            if (!audOk) {
                throw ApiException.unauthorized(MessageKey.AUTH_GOOGLE_INVALID_AUDIENCE);
            }
            if (isExpired(payload.get("exp"))) {
                throw invalid.get();
            }
            if (!(payload.get("email") instanceof String e)) {
                throw invalid.get();
            }
            email = e;
            name = payload.get("name") instanceof String n ? n : "";
            // ID tokens carry email_verified as a boolean; parse defensively in case it arrives as a string
            emailVerified = "true".equals(String.valueOf(payload.get("email_verified")));
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("Google id_token verification error: {}", ex.getMessage());
            throw ApiException.unauthorized(MessageKey.AUTH_GOOGLE_NO_EMAIL);
        }

        // Outside the try: DB / Redis failures must surface as themselves, not as an invalid-token 401
        User user = findOrCreateUserByEmail(User.normalizeEmail(email), name, "google", emailVerified);
        return buildAuthResponse(user);
    }

    // ── LINE OAuth ────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public AuthResponse lineLogin(String code, String redirectUri) {
        if (lineChannelId == null || lineChannelId.isBlank()) {
            throw ApiException.badRequest(MessageKey.AUTH_LINE_NOT_CONFIGURED);
        }

        MultiValueMap<String, String> tokenBody = new LinkedMultiValueMap<>();
        tokenBody.add("grant_type", "authorization_code");
        tokenBody.add("code", code);
        tokenBody.add("redirect_uri", redirectUri);
        tokenBody.add("client_id", lineChannelId);
        tokenBody.add("client_secret", lineChannelSecret);

        var tokenResponse = restClient.post().uri(lineTokenUrl)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(tokenBody)
                .retrieve()
                .body(java.util.Map.class);

        if (tokenResponse == null || tokenResponse.get("access_token") == null) {
            throw ApiException.unauthorized(MessageKey.AUTH_LINE_TOKEN_EXCHANGE_FAILED);
        }

        String accessToken = (String) tokenResponse.get("access_token");

        var lineProfile = restClient.get().uri(lineProfileUrl)
                .header("Authorization", "Bearer " + accessToken).retrieve().body(java.util.Map.class);

        if (lineProfile == null || lineProfile.get("userId") == null) {
            throw ApiException.unauthorized(MessageKey.AUTH_LINE_PROFILE_FAILED);
        }

        String email = "";
        String idToken = (String) tokenResponse.get("id_token");
        if (idToken != null) {
            try {
                MultiValueMap<String, String> verifyBody = new LinkedMultiValueMap<>();
                verifyBody.add("id_token", idToken);
                verifyBody.add("client_id", lineChannelId);

                var verifyResponse = restClient.post().uri(lineVerifyUrl)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body(verifyBody)
                        .retrieve()
                        .body(java.util.Map.class);

                if (verifyResponse != null && verifyResponse.get("email") != null) {
                    email = (String) verifyResponse.get("email");
                }
            } catch (Exception ex) {
                log.warn("Failed to get email from LINE id_token: {}", ex.getMessage());
            }
        }

        if (email.isBlank()) {
            email = "line_" + lineProfile.get("userId") + "@line.oauth";
        }

        String displayName = (String) lineProfile.getOrDefault("displayName", "");

        // LINE's verify endpoint only returns the email when it is verified; otherwise the synthesized
        // line_<userId>@line.oauth is unique and cannot be set by an attacker to a victim's real email -> both cases are safely treated as verified
        User user = findOrCreateUserByEmail(User.normalizeEmail(email), displayName, "line", true);
        return buildAuthResponse(user);
    }

    // ── Apple OAuth ───────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public AuthResponse appleLogin(String idToken, String fullName) {
        // Without a configured client id the audience cannot be checked, so a token minted for any app would pass
        if (appleClientId == null || appleClientId.isBlank()) {
            throw ApiException.badRequest(MessageKey.AUTH_APPLE_NOT_CONFIGURED);
        }
        // P6-#16: JWKS fetching (external call + null guard + 5xx/connection failure classification) stays outside the try,
        // so a 503 external error from an Apple/network failure is not swallowed by the broad catch below and misreported as a 401 login failure.
        List<java.util.Map<String, Object>> keys = fetchJwks(appleJwksUrl, "Apple", false);

        try {
            var payload = verifyRs256(idToken, keys, appleJwksUrl, "Apple",
                    () -> ApiException.unauthorized(MessageKey.AUTH_APPLE_INVALID_TOKEN),
                    () -> ApiException.unauthorized(MessageKey.AUTH_APPLE_KEY_NOT_FOUND),
                    () -> ApiException.unauthorized(MessageKey.AUTH_APPLE_INVALID_SIGNATURE));

            if (!"https://appleid.apple.com".equals(payload.get("iss"))) {
                throw ApiException.unauthorized(MessageKey.AUTH_APPLE_INVALID_ISSUER);
            }
            if (!appleClientId.equals(payload.get("aud"))) {
                throw ApiException.unauthorized(MessageKey.AUTH_APPLE_INVALID_AUDIENCE);
            }
            if (isExpired(payload.get("exp"))) {
                throw ApiException.unauthorized(MessageKey.AUTH_APPLE_TOKEN_EXPIRED);
            }

            // TODO(apple-nonce): full anti-replay needs "backend issues nonce -> store in Redis -> client passes it to Apple
            //   -> Apple returns the nonce in id_token -> backend compares and consumes it here". The controller/DTO has no
            //   nonce issuing flow yet, and the nonce store infrastructure is out of scope for this batch. This is a placeholder
            //   check: if the payload contains a nonce, log it; enforce the comparison once the client and nonce store are ready.
            //   This batch relies on email_verified as the actual fix.
            Object nonce = payload.get("nonce");
            if (nonce != null) {
                log.debug("Apple id_token contains a nonce; it will be checked once the nonce store is ready (placeholder only)");
            }

            String email = (String) payload.get("email");
            if (email == null)
                throw ApiException.unauthorized(MessageKey.AUTH_APPLE_NO_EMAIL);

            // Apple id_token usually returns email_verified as the string "true", occasionally as boolean true; parse defensively
            boolean emailVerified = "true".equals(String.valueOf(payload.get("email_verified")));

            User user = findOrCreateUserByEmail(User.normalizeEmail(email),
                    fullName != null ? fullName : "", "apple", emailVerified);
            return buildAuthResponse(user);

        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Apple login error: {}", ex.getMessage());
            throw ApiException.unauthorized(MessageKey.AUTH_APPLE_LOGIN_FAILED);
        }
    }

    /**
     * Verifies an RS256 JWT against the given JWKS and returns its payload; claims (iss / aud / exp) are left to the caller.
     * {@code invalidToken} covers a malformed token, a header without kid or with an alg other than RS256 (P6-#16: credential problem -> 401).
     * A kid missing from {@code keys} triggers one refetch of {@code jwksUrl} before giving up with {@code keyNotFound}.
     */
    @SuppressWarnings("unchecked")
    private java.util.Map<String, Object> verifyRs256(String idToken, List<java.util.Map<String, Object>> keys,
            String jwksUrl, String provider,
            java.util.function.Supplier<ApiException> invalidToken, java.util.function.Supplier<ApiException> keyNotFound,
            java.util.function.Supplier<ApiException> invalidSignature) throws Exception {
        String[] parts = idToken.split("\\.");
        if (parts.length != 3)
            throw invalidToken.get();

        byte[] headerBytes = java.util.Base64.getUrlDecoder().decode(parts[0]);
        var headerMap = JSON_MAPPER.readValue(headerBytes, java.util.Map.class);
        // Only RS256 is accepted, whatever the header claims (no "none", no HMAC with the public key)
        if (!"RS256".equals(headerMap.get("alg")))
            throw invalidToken.get();
        String kid = (String) headerMap.get("kid");
        if (kid == null)
            throw invalidToken.get();

        var matchingKey = findKey(keys, kid);
        if (matchingKey == null) {
            // The provider may have rotated its keys since the cache was filled: refetch once and retry
            matchingKey = findKey(fetchJwks(jwksUrl, provider, true), kid);
        }
        if (matchingKey == null)
            throw keyNotFound.get();

        String n = (String) matchingKey.get("n");
        String e = (String) matchingKey.get("e");
        byte[] nBytes = java.util.Base64.getUrlDecoder().decode(n);
        byte[] eBytes = java.util.Base64.getUrlDecoder().decode(e);
        var keyFactory = java.security.KeyFactory.getInstance("RSA");
        var rsaKey = keyFactory.generatePublic(
                new java.security.spec.RSAPublicKeySpec(new java.math.BigInteger(1, nBytes),
                        new java.math.BigInteger(1, eBytes)));

        var sig = java.security.Signature.getInstance("SHA256withRSA");
        sig.initVerify(rsaKey);
        sig.update((parts[0] + "." + parts[1]).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        boolean valid = sig.verify(java.util.Base64.getUrlDecoder().decode(parts[2]));
        if (!valid)
            throw invalidSignature.get();

        byte[] payloadBytes = java.util.Base64.getUrlDecoder().decode(parts[1]);
        return JSON_MAPPER.readValue(payloadBytes, java.util.Map.class);
    }

    private static java.util.Map<String, Object> findKey(List<java.util.Map<String, Object>> keys, String kid) {
        return keys.stream().filter(k -> kid.equals(k.get("kid"))).findFirst().orElse(null);
    }

    /** True when exp is missing or more than {@link #ID_TOKEN_CLOCK_SKEW} in the past. */
    private static boolean isExpired(Object exp) {
        return !(exp instanceof Number n)
                || Instant.ofEpochSecond(n.longValue()).plus(ID_TOKEN_CLOCK_SKEW).isBefore(Instant.now());
    }

    /**
     * Fetches and caches a provider's public key list (JWKS).
     *
     * <p>P6-#16 / P6-#21:</p>
     * <ul>
     *   <li><b>Cache</b>: returns directly on a fresh hit (10-minute TTL, per URL), so each login does not hit the JWKS endpoint.</li>
     *   <li><b>External failure classification</b>: 5xx / connection failures talking to the JWKS endpoint (all
     *       {@link RestClientException} subclasses, including {@code HttpServerErrorException}, {@code ResourceAccessException})
     *       -> throw 503 {@link MessageKey#COMMON_SERVICE_UNAVAILABLE}, <b>not</b> misreported as a 401 login failure.</li>
     *   <li><b>Null guard</b>: a null response body, or null or empty {@code keys} -> treated as an abnormal upstream response -> 503.</li>
     * </ul>
     *
     * @param refresh bypass a fresh cache (kid miss); still served from cache within {@link #JWKS_REFETCH_COOLDOWN}
     * @return the parsed, non-empty public key list
     */
    @SuppressWarnings("unchecked")
    private List<java.util.Map<String, Object>> fetchJwks(String url, String provider, boolean refresh) {
        JwksCache cached = jwksCache.get(url);
        if (cached != null && cached.isYoungerThan(refresh ? JWKS_REFETCH_COOLDOWN : JWKS_CACHE_TTL)) {
            return cached.keys();
        }

        java.util.Map<String, Object> jwks;
        try {
            jwks = restClient.get().uri(url).retrieve().body(java.util.Map.class);
        } catch (RestClientException ex) {
            // JWKS endpoint 5xx / connection timeout / DNS failure -> upstream/external service failure, retryable (503), not a credential problem
            log.warn("Failed to fetch {} JWKS (external service): {}", provider, ex.getMessage());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, MessageKey.COMMON_SERVICE_UNAVAILABLE);
        }

        if (jwks == null || jwks.get("keys") == null) {
            log.warn("Abnormal {} JWKS response: body or keys is null", provider);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, MessageKey.COMMON_SERVICE_UNAVAILABLE);
        }

        List<java.util.Map<String, Object>> keys = (List<java.util.Map<String, Object>>) jwks.get("keys");
        if (keys.isEmpty()) {
            log.warn("Abnormal {} JWKS response: keys is empty", provider);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, MessageKey.COMMON_SERVICE_UNAVAILABLE);
        }

        jwksCache.put(url, new JwksCache(keys, Instant.now()));
        return keys;
    }

    // ── Shared ────────────────────────────────────────────────────────

    /**
     * Finds or creates a User by email.
     *
     * <p>{@code emailVerified} is the key gate against account takeover:</p>
     * <ul>
     *   <li><b>Verified</b> (true): the OAuth provider vouches for this email (or the user proved ownership
     *       via OTP), so merging into an existing account or creating a new one is safe -> find-or-create.</li>
     *   <li><b>Unverified</b> (false): an attacker can set a victim's email at the OAuth provider, so we
     *       <b>must not</b> merge into an existing account nor create a new one; throw
     *       {@link MessageKey#AUTH_EMAIL_NOT_VERIFIED}.</li>
     * </ul>
     * <p>This is the only place an email is proven to be the user's, so pending email Invitees and Group members of it are
     * tied to the user here, in the same transaction as the find-or-create (Polls locked before Groups); rerunning it binds nothing new.</p>
     */
    User findOrCreateUserByEmail(String email, String fullName, String authMethod, boolean emailVerified) {
        if (!emailVerified) {
            // Email not verified by the provider -> refuse to merge or create, preventing account takeover
            throw ApiException.unauthorized(MessageKey.AUTH_EMAIL_NOT_VERIFIED);
        }
        // Programmatic transaction: callers are in this class, so @Transactional here would be skipped
        return transactionTemplate.execute(status -> {
            User user = userRepository.findByEmail(email).orElseGet(() -> {
                User newUser = User.builder()
                        .email(email)
                        .username(generateUsername(email.split("@")[0]))
                        .displayName((fullName != null && !fullName.isBlank()) ? fullName : email.split("@")[0])
                        .authMethod(authMethod)
                        .avatarUrl(BotAvatar.random())
                        .build();
                return userRepository.save(newUser);
            });
            voteService.bindEmailInvitees(user);
            groupService.bindEmailMembers(user);
            return user;
        });
    }

    User findOrCreateUserByPhone(String phone) {
        String normalizedPhone = User.normalizePhone(phone);
        return userRepository.findByPhoneNumber(normalizedPhone).orElseGet(() -> {
            User newUser = User.builder()
                    .phoneNumber(normalizedPhone)
                    .username(generateUsername("user"))
                    .displayName(normalizedPhone)
                    .authMethod("phone_otp")
                    .avatarUrl(BotAvatar.random())
                    .build();
            return userRepository.save(newUser);
        });
    }

    /**
     * Generates a unique username from the email local part (or a fixed prefix): keeps only [a-z0-9_], at most 40 chars, plus a 4-digit random suffix to avoid collisions; never returns a reserved username.
     */
    private String generateUsername(String seed) {
        String base = seed.toLowerCase().replaceAll("[^a-z0-9_]", "");
        if (base.isBlank()) base = "user";
        if (base.length() > 40) base = base.substring(0, 40);
        String candidate;
        do {
            candidate = base + "_" + "%04d".formatted(RANDOM.nextInt(10000));
        } while (ReservedUsernames.contains(candidate) || userRepository.existsByUsername(candidate));
        return candidate;
    }

    public AuthResponse buildAuthResponse(User user) {
        return buildAuthResponse(user, false);
    }

    private AuthResponse buildAuthResponse(User user, boolean adminScope) {
        // Ban check: all login paths (OTP/Google/LINE/Apple, phone) funnel through here, so they are blocked in one place
        if (!user.isActive()) {
            throw ApiException.forbidden(MessageKey.AUTH_ACCOUNT_BANNED);
        }
        user.setLastLoginAt(java.time.OffsetDateTime.now());
        userRepository.save(user);
        List<String> roles = List.of(user.getRoles());
        String token = jwtUtil.generateToken(user.getId().toString(), user.getEmail(), roles, adminScope);
        String refreshToken = refreshTokenService.generate(user.getId().toString(), user.getEmail(), roles, adminScope);
        return new AuthResponse(token, refreshToken,
                new AuthResponse.UserInfo(user.getId().toString(), user.getEmail(), user.getUsername(),
                        user.getDisplayName(), roles, user.getAvatarUrl(), user.getPhoneNumber()));
    }

    public AuthResponse refresh(String refreshToken) {
        try {
            RefreshTokenService.RotateResult result = refreshTokenService.rotate(refreshToken);
            if (result == null) {
                throw ApiException.unauthorized(MessageKey.AUTH_REFRESH_TOKEN_INVALID);
            }
            return new AuthResponse(result.newAccessToken(), result.newRefreshToken(), null);
        } catch (com.vomatt.common.security.RefreshTokenReuseException e) {
            throw ApiException.unauthorized(MessageKey.AUTH_SUSPICIOUS_LOGIN);
        }
    }

    public void logout(String refreshToken) {
        refreshTokenService.revoke(refreshToken);
    }

    private String generateOtpCode() {
        return "%06d".formatted(100000 + RANDOM.nextInt(900000));
    }
}
