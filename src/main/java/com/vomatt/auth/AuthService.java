package com.vomatt.auth;

import com.vomatt.auth.dto.AuthResponse;
import com.vomatt.auth.dto.PasskeyLoginVerifyRequest;
import com.vomatt.common.constant.LoginUnlock;
import com.vomatt.common.constant.ReservedUsernames;
import com.vomatt.common.email.EmailService;
import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.redis.RedisService;
import com.vomatt.common.security.AppKey;
import com.vomatt.common.security.RefreshTokenReuseException;
import com.vomatt.common.security.RefreshTokenService;
import com.vomatt.common.sms.SmsService;
import com.vomatt.common.util.TextUtil;
import com.vomatt.entity.User;
import com.vomatt.invitelists.InviteListService;
import com.vomatt.repository.UserRepository;
import com.vomatt.users.BotAvatar;
import com.vomatt.votes.VoteService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final SecureRandom RANDOM = new SecureRandom();

    // P6-#21: Jackson 3 JsonMapper/ObjectMapper is thread-safe and should be reused as a singleton
    // (each build recreates the serializer/deserializer caches, which is wasteful). Apple login
    // shares this singleton for parsing the JWT header / payload.
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();
    /** Upstream label for LINE calls in logs. */
    private static final String LINE = "LINE";

    // P6-#21: short-TTL in-memory cache for the Apple / Google JWKS, so each login does not hit the JWKS endpoint.
    // Self-contained in AuthService (no Redis); one holder + timestamp per JWKS URL. With mostly
    // reads and rare writes, an occasional duplicate fetch is acceptable (no lock needed).
    private static final Duration JWKS_CACHE_TTL = Duration.ofMinutes(10);
    // A kid miss refetches the JWKS (the provider may have rotated keys), but at most once per minute per URL,
    // so forged kids cannot turn every login attempt into an outbound fetch
    private static final Duration JWKS_REFETCH_COOLDOWN = Duration.ofMinutes(1);
    // Tolerated clock drift between us and the provider when checking id_token exp
    private static final Duration ID_TOKEN_CLOCK_SKEW = Duration.ofSeconds(60);

    private static final Set<String> GOOGLE_ISSUERS = Set.of("accounts.google.com", "https://accounts.google.com");

    /** Hashes of Apple / Google id_tokens already exchanged: each one signs in once (LINE codes are single-use at LINE). */
    private static final String OAUTH_USED_NS = "oauth:used";

    /** JWKS cache holder: keys is the parsed public key list, fetchedAt is the fetch time. */
    private record JwksCache(List<Map<String, Object>> keys, Instant fetchedAt) {
        boolean isYoungerThan(Duration age) {
            return Duration.between(fetchedAt, Instant.now()).compareTo(age) < 0;
        }
    }

    private final Map<String, JwksCache> jwksCache = new ConcurrentHashMap<>();

    private final UserRepository userRepository;
    private final VoteService voteService;
    private final InviteListService inviteListService;
    private final TransactionTemplate transactionTemplate;
    private final RedisService redisService;
    private final EmailService emailService;
    private final SmsService smsService;
    private final RestClient restClient;
    private final RefreshTokenService refreshTokenService;
    private final AppKey appKey;
    private final PasskeyService passkeyService;

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

    /**
     * OTP keys are {@code <namespace>:<SHA-256(identifier)>} and hold only an HMAC of the code (see {@link #otpHash}),
     * so neither a key listing nor a value dump of Redis yields an email, a phone number or a code that signs in.
     */
    private static final String OTP_EMAIL_NS = "otp:email";
    private static final String OTP_PHONE_NS = "otp:phone";
    private static final String OTP_ADMIN_EMAIL_NS = "otp:admin-email";

    @Value("${otp.ttl-seconds:180}")
    private long otpTtlSeconds;

    @Value("${otp.resend-cooldown-seconds:180}")
    private long otpResendCooldownSeconds;

    @Value("${otp.daily-limit.email:20}")
    private long otpDailyLimitEmail;

    @Value("${otp.daily-limit.phone:5}")
    private long otpDailyLimitPhone;

    /**
     * Fixed consumer OTP for {@value #TEST_EMAIL_DOMAIN} accounts (load-test accounts), sent nowhere; blank turns it off.
     * Lockouts, cooldown and daily quota still apply.
     */
    @Value("${otp.test-code:}")
    private String otpTestCode;

    /** Reserved TLD (RFC 2606), so no real person can own an address in it. */
    static final String TEST_EMAIL_DOMAIN = "@vomatt.test";

    public void sendOtp(String email, String phone, String type, String clientIp) {
        String otpType = (type != null) ? type : "login";
        String code = generateOtpCode();

        if (StringUtils.hasText(email)) {
            sendEmailOtp(User.normalizeEmail(email), code, clientIp);
        } else if (StringUtils.hasText(phone)) {
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
        // Lock first: a locked pair is refused from Redis alone, without a DB hit per attempt
        rejectIfLocked(OTP_ADMIN_EMAIL_NS, attemptKey(normalizedEmail, clientIp));
        requireExistingAdminByEmail(normalizedEmail);
        sendEmailOtp(OTP_ADMIN_EMAIL_NS, normalizedEmail, "login", generateOtpCode(), clientIp);
    }

    private void sendEmailOtp(String normalizedEmail, String code, String clientIp) {
        // Checked before the DB lookup, for the same reason as sendAdminOtp; sendEmailOtp checks again (Redis only)
        rejectIfLocked(OTP_EMAIL_NS, attemptKey(normalizedEmail, clientIp));
        String otpType = userRepository.existsByEmail(normalizedEmail) ? "login" : "signup";
        sendEmailOtp(OTP_EMAIL_NS, normalizedEmail, otpType, code, clientIp);
    }

    private void sendEmailOtp(String namespace, String normalizedEmail, String otpType, String code, String clientIp) {
        String attemptKey = attemptKey(normalizedEmail, clientIp);
        rejectIfLocked(namespace, attemptKey);

        String redisKey = redisKey(normalizedEmail);
        acquireResendCooldown(namespace, redisKey);
        // Computed once: the refund on failure must hit the same day's key even across UTC midnight
        String quotaKey = redisKey + ":" + TextUtil.utcDay();
        takeDailyQuota(namespace, quotaKey, otpDailyLimitEmail);
        boolean testAccount = OTP_EMAIL_NS.equals(namespace) && StringUtils.hasText(otpTestCode)
                && normalizedEmail.endsWith(TEST_EMAIL_DOMAIN);
        if (testAccount) {
            code = otpTestCode;
        }
        storeOtp(namespace, redisKey, quotaKey, attemptKey, new OtpRecord(otpHash(normalizedEmail, code), otpType, Instant.now(), 0));
        if (testAccount) {
            return;
        }

        try {
            if (OTP_ADMIN_EMAIL_NS.equals(namespace)) {
                emailService.sendAdminLoginEmail(normalizedEmail, code);
            } else if ("signup".equals(otpType)) {
                emailService.sendPreSignupEmail(normalizedEmail, code);
            } else {
                emailService.sendVerificationEmail(normalizedEmail, code);
            }
        } catch (Exception ex) {
            // The code never reached the user: drop it and shorten the cooldown so a retry is possible soon
            releaseUndeliveredOtp(namespace, redisKey, quotaKey);
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

        String redisKey = redisKey(phone);
        acquireResendCooldown(OTP_PHONE_NS, redisKey);
        String quotaKey = redisKey + ":" + TextUtil.utcDay();
        takeDailyQuota(OTP_PHONE_NS, quotaKey, otpDailyLimitPhone);
        storeOtp(OTP_PHONE_NS, redisKey, quotaKey, attemptKey, new OtpRecord(otpHash(phone, code), otpType, Instant.now(), 0));

        try {
            smsService.sendOtp(phone, code);
        } catch (Exception ex) {
            releaseUndeliveredOtp(OTP_PHONE_NS, redisKey, quotaKey);
            log.warn("Failed to send OTP SMS: {}", ex.getMessage());
            throw ApiException.badRequest(MessageKey.AUTH_SMS_SEND_FAILED);
        }
    }

    public AuthResponse verifyOtp(String email, String phone, String code, String clientIp) {
        if (StringUtils.hasText(email)) {
            return verifyEmailOtp(User.normalizeEmail(email), code, clientIp);
        } else if (StringUtils.hasText(phone)) {
            return verifyPhoneOtp(User.normalizePhone(phone), code, clientIp);
        } else {
            throw ApiException.badRequest(MessageKey.AUTH_IDENTIFIER_REQUIRED);
        }
    }

    /** Admin console login, step 2: the only path that issues admin-scope tokens. Never creates an account. */
    public AuthResponse verifyAdminOtp(String email, String code, String clientIp) {
        String normalizedEmail = requireEmail(email);
        rejectIfLocked(OTP_ADMIN_EMAIL_NS, attemptKey(normalizedEmail, clientIp));
        // P5-#13: the role check runs before the OTP is consumed, so a non-admin or suspended attempt burns nothing
        User user = requireExistingAdminByEmail(normalizedEmail);
        consumeOtp(OTP_ADMIN_EMAIL_NS, normalizedEmail, code, clientIp);
        return buildAuthResponse(user, true, false);
    }

    private static String requireEmail(String email) {
        if (!StringUtils.hasText(email)) {
            throw ApiException.badRequest(MessageKey.AUTH_IDENTIFIER_REQUIRED);
        }
        return User.normalizeEmail(email);
    }

    /** Requires an existing, active account for the email with the ADMIN role; otherwise throws 403. */
    private User requireExistingAdminByEmail(String normalizedEmail) {
        return requireActiveAdmin(userRepository.findByEmail(normalizedEmail));
    }

    /** 403 {@code auth.admin.forbidden} without the ADMIN role, 403 {@code auth.account_banned} when suspended. */
    private static User requireActiveAdmin(Optional<User> candidate) {
        User user = candidate.filter(User::isAdmin)
                .orElseThrow(() -> ApiException.forbidden(MessageKey.AUTH_ADMIN_FORBIDDEN));
        if (!user.isActive()) {
            throw ApiException.forbidden(MessageKey.AUTH_ACCOUNT_BANNED);
        }
        return user;
    }

    // ── Passkey ──────────────────────────────────────────────────────

    /** Usernameless passkey sign-in; like every consumer login it never grants admin scope. */
    public AuthResponse verifyPasskey(PasskeyLoginVerifyRequest request) {
        return buildAuthResponse(passkeyService.authenticate(request), false, false);
    }

    /** Admin console passkey sign-in: the passkey counterpart of {@link #verifyAdminOtp}, with the same role checks. */
    public AuthResponse verifyAdminPasskey(PasskeyLoginVerifyRequest request) {
        return buildAuthResponse(requireActiveAdmin(Optional.of(passkeyService.authenticate(request))), true, false);
    }

    private AuthResponse verifyEmailOtp(String normalizedEmail, String code, String clientIp) {
        OtpRecord otp = consumeOtp(OTP_EMAIL_NS, normalizedEmail, code, clientIp);

        // The user proved ownership of the email via OTP -> treat as verified
        FoundUser found = findOrCreateUserByEmail(normalizedEmail, null, "email_otp", true);
        User user = found.user();

        AuthResponse response = buildAuthResponse(user, false, found.created());
        if ("signup".equals(otp.type())) {
            // Sent only after the login succeeded, in the background so SMTP latency does not delay the response
            emailService.sendWelcomeEmail(normalizedEmail, user.getDisplayName(), LocaleContextHolder.getLocale());
        }
        return response;
    }

    private AuthResponse verifyPhoneOtp(String phone, String code, String clientIp) {
        consumeOtp(OTP_PHONE_NS, phone, code, clientIp);
        FoundUser found = findOrCreateUserByPhone(phone);
        return buildAuthResponse(found.user(), false, found.created());
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

    /** Namespace suffix for the per-identifier, per-UTC-day send counter (all IPs), so one address cannot be flooded. */
    private static final String OTP_DAILY_SUFFIX = ":daily";

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

    private OtpRecord consumeOtp(String namespace, String identifier, String code, String clientIp) {
        String attemptKey = attemptKey(identifier, clientIp);
        rejectIfLocked(namespace, attemptKey);

        String key = redisKey(identifier);
        OtpRecord rec = redisService.get(namespace, key, OtpRecord.class);

        if (rec == null) {
            throw ApiException.unauthorized(MessageKey.AUTH_OTP_INVALID_OR_EXPIRED);
        }

        // Every attempt is counted with an atomic INCR BEFORE the code is compared, so concurrent guesses cannot
        // all slip past the limit (P1-#7). TTL matches the OTP's remaining lifetime (at least 1 second); a resend extends it.
        String failNs = namespace + OTP_FAIL_SUFFIX;
        Duration counterTtl = Duration.ofSeconds(Math.max(otpSecondsLeft(rec), 1));
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

        // P5-#19: constant-time comparison of the two hashes (equal length), so no timing side-channel leaks a prefix
        boolean codeMatches = MessageDigest.isEqual(
                rec.codeHash().getBytes(StandardCharsets.UTF_8),
                otpHash(identifier, code).getBytes(StandardCharsets.UTF_8));
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
            long left = otpSecondsLeft(consumed);
            if (left > 0) {
                redisService.set(namespace, key, consumed, Duration.ofSeconds(left));
            }
            throw ApiException.unauthorized(MessageKey.AUTH_OTP_INVALID_OR_EXPIRED);
        }
        redisService.delete(failNs, attemptKey);
        redisService.delete(namespace + OTP_LEVEL_SUFFIX, attemptKey);
        return rec;
    }

    private long otpSecondsLeft(OtpRecord otp) {
        return otpTtlSeconds - Duration.between(otp.createdAt(), Instant.now()).getSeconds();
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
     * Admin unlock: clears the chosen {@link LoginUnlock} targets for one identifier on one channel, in one multi-key
     * DEL per key family so a long index costs a handful of round trips.
     * <ul>
     *   <li>{@code lock}: the lock, its doubling level and the per-IP wrong-code counter (a lock left with its counter at
     *       the limit would still refuse the right code)</li>
     *   <li>{@code attempts}: the per-IP wrong-code counters, plus the current OTP and its guess budget; the OTP is
     *       discarded rather than given fresh guesses, so the user requests a new one</li>
     *   <li>{@code cooldown}: the resend wait</li>
     * </ul>
     *
     * @param identifier normalized email or E.164 phone, as stored on the account
     * @param clientIp   only this IP (any spelling of it); null for every IP that guessed wrong since the index existed
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
        String key = redisKey(identifier);
        Collection<String> attemptKeys = clientIp != null ? List.of(attemptKey(identifier, clientIp))
                : redisService.hGetAll(indexNs, key).keySet();
        boolean lock = targets.contains(LoginUnlock.TARGET_LOCK);
        boolean attempts = targets.contains(LoginUnlock.TARGET_ATTEMPTS);
        long removed = 0;
        if (lock) {
            removed += redisService.deleteAll(namespace + OTP_LOCK_SUFFIX, attemptKeys)
                    + redisService.deleteAll(namespace + OTP_LEVEL_SUFFIX, attemptKeys);
        }
        if (lock || attempts) {
            removed += redisService.deleteAll(namespace + OTP_FAIL_SUFFIX, attemptKeys);
        }
        if (attempts) {
            OtpRecord otp = redisService.getAndDelete(namespace, key, OtpRecord.class);
            if (otp != null) {
                removed += 1 + redisService.deleteAll(namespace + OTP_GUESS_SUFFIX,
                        List.of(key + ":" + otp.createdAt().toEpochMilli()));
            }
        }
        if (targets.contains(LoginUnlock.TARGET_COOLDOWN)) {
            removed += redisService.deleteAll(namespace + OTP_COOLDOWN_SUFFIX, List.of(key));
        }
        // Lock and counter are both gone for these keys, so the index has nothing left to point at
        if (lock) {
            if (clientIp == null) {
                redisService.delete(indexNs, key);
            } else {
                redisService.hDelete(indexNs, key, attemptKeys.iterator().next());
            }
        }
        return (int) removed;
    }

    private void rejectIfLocked(String namespace, String attemptKey) {
        // Fails closed: a Redis error must not let a locked identifier guess again
        if (redisService.hasKeyOrThrow(namespace + OTP_LOCK_SUFFIX, attemptKey)) {
            throw ApiException.unauthorized(MessageKey.AUTH_OTP_TOO_MANY_ATTEMPTS);
        }
    }

    /**
     * Redis key for one identifier guessing from one client IP; hashed so neither part can break the key format. The IP
     * is canonicalized first, so an admin unlocking "2001:DB8::1" hits the key the server wrote for "2001:db8:0:0:0:0:0:1".
     */
    static String attemptKey(String identifier, String clientIp) {
        return AppKey.sha256Hex(identifier + "\n" + canonicalIp(clientIp));
    }

    /** Redis key part for one identifier: a hash, so OTP keys never list emails or phone numbers. */
    static String redisKey(String identifier) {
        return AppKey.sha256Hex(identifier);
    }

    /**
     * What is stored for an OTP instead of the code: {@code HMAC(derive("otp"), identifier:code)}. A Redis dump cannot be
     * used to sign in, and without the key no table of the 10^6 codes can be precomputed; the identifier keeps two
     * users' equal codes from hashing alike.
     */
    private String otpHash(String identifier, String code) {
        return AppKey.hmacHex(appKey.derive(OTP_KEY_LABEL), identifier + ":" + code);
    }

    private static final String OTP_KEY_LABEL = "otp";

    /** An IP literal in one spelling (InetAddress.ofLiteral never does DNS); anything else is kept as is. */
    static String canonicalIp(String ip) {
        if (ip == null) {
            return null;
        }
        try {
            return InetAddress.ofLiteral(ip.trim()).getHostAddress();
        } catch (IllegalArgumentException e) {
            return ip;
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
     * Counts this send against the identifier's daily cap, after the cooldown slot is taken so a user clicking resend
     * during the cooldown does not burn the cap. Over it the cooldown stays set, which is harmless: no OTP is sent
     * either way. Fails closed (RedisOperationException) like the OTP write itself.
     */
    private void takeDailyQuota(String namespace, String quotaKey, long limit) {
        long sent = redisService.incrementAndExpire(namespace + OTP_DAILY_SUFFIX, quotaKey, Duration.ofDays(1));
        if (sent > limit) {
            throw ApiException.tooManyRequests(MessageKey.AUTH_OTP_DAILY_LIMIT);
        }
    }

    /** Gives back the slot taken by {@link #takeDailyQuota} when no OTP went out. Fail-soft: it must not mask the real failure. */
    private void refundDailyQuota(String namespace, String quotaKey) {
        try {
            redisService.incrementAndExpire(namespace + OTP_DAILY_SUFFIX, quotaKey, -1, Duration.ofDays(1));
        } catch (RuntimeException ex) {
            log.warn("Failed to refund OTP daily quota: {}", ex.getMessage());
        }
    }

    /**
     * Stores the OTP and, in the same transaction, extends this identifier + IP's failure counter to outlive it, so a
     * resend never grants fresh guesses (the counter is not reset). If that fails, releases the cooldown just claimed.
     */
    private void storeOtp(String namespace, String key, String quotaKey, String attemptKey, OtpRecord record) {
        Duration ttl = Duration.ofSeconds(otpTtlSeconds);
        try {
            redisService.execInTransaction(List.of(
                    RedisService.RedisWrite.set(namespace, key, record, ttl),
                    RedisService.RedisWrite.expire(namespace + OTP_FAIL_SUFFIX, attemptKey, ttl)));
        } catch (RuntimeException ex) {
            // Every cleanup is fail-soft and runs in full: Redis is likely down, and the store failure is the one to report
            refundDailyQuota(namespace, quotaKey);
            try {
                redisService.delete(namespace + OTP_COOLDOWN_SUFFIX, key);
            } catch (RuntimeException cleanup) {
                log.warn("Failed to release OTP cooldown after store failure: {}", cleanup.getMessage());
            }
            throw ex;
        }
    }

    /**
     * Drops an OTP whose delivery failed and shortens its cooldown to {@link #OTP_FAILED_SEND_COOLDOWN}, so the user can
     * retry soon but a failing provider is not hit on every request. Fail-soft: a Redis error must not mask the send failure.
     */
    private void releaseUndeliveredOtp(String namespace, String key, String quotaKey) {
        refundDailyQuota(namespace, quotaKey);
        try {
            // Cooldown first: if the delete then fails, the user can at least retry soon; the other order could leave a
            // full-length cooldown with no OTP behind it
            redisService.set(namespace + OTP_COOLDOWN_SUFFIX, key, availableAt(OTP_FAILED_SEND_COOLDOWN), OTP_FAILED_SEND_COOLDOWN);
            redisService.delete(namespace, key);
        } catch (RuntimeException cleanup) {
            log.warn("Failed to release undelivered OTP: {}", cleanup.getMessage());
        }
    }

    private static String availableAt(Duration cooldown) {
        return Long.toString(Instant.now().plus(cooldown).getEpochSecond());
    }

    // ── Google OAuth ──────────────────────────────────────────────────

    public AuthResponse googleLogin(String idToken) {
        if (!StringUtils.hasText(googleClientId)) {
            throw ApiException.badRequest(MessageKey.AUTH_GOOGLE_NOT_CONFIGURED);
        }
        // JWKS outage -> 503 (thrown before the try, so it is not reported as an invalid token)
        List<Map<String, Object>> keys = fetchJwks(googleJwksUrl, "Google", false);

        String email;
        String name;
        boolean emailVerified;
        Map<String, Object> payload;
        try {
            // Every local verification failure maps to auth.google.no_email ("token invalid"), except a wrong audience
            Supplier<ApiException> invalid = () -> ApiException.unauthorized(MessageKey.AUTH_GOOGLE_NO_EMAIL);
            payload = verifyRs256(idToken, keys, googleJwksUrl, "Google", invalid, invalid, invalid);
            if (!GOOGLE_ISSUERS.contains(payload.get("iss"))) {
                throw invalid.get();
            }
            // google.client-id may list several client ids (web / iOS / Android), comma-separated
            boolean audOk = Arrays.stream(googleClientId.split(","))
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
        consumeIdToken(idToken, payload.get("exp"));

        // Outside the try: DB / Redis failures must surface as themselves, not as an invalid-token 401
        FoundUser found = findOrCreateUserByEmail(User.normalizeEmail(email), name, "google", emailVerified);
        return buildAuthResponse(found.user(), false, found.created());
    }

    // ── LINE OAuth ────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public AuthResponse lineLogin(String code, String redirectUri) {
        if (!StringUtils.hasText(lineChannelId)) {
            throw ApiException.badRequest(MessageKey.AUTH_LINE_NOT_CONFIGURED);
        }

        MultiValueMap<String, String> tokenBody = new LinkedMultiValueMap<>();
        tokenBody.add("grant_type", "authorization_code");
        tokenBody.add("code", code);
        tokenBody.add("redirect_uri", redirectUri);
        tokenBody.add("client_id", lineChannelId);
        tokenBody.add("client_secret", lineChannelSecret);

        Map<String, Object> tokenResponse = callLine(() -> postForm(lineTokenUrl, tokenBody),
                MessageKey.AUTH_LINE_TOKEN_EXCHANGE_FAILED);

        // instanceof, not a cast: a malformed LINE response is a failed exchange (401), not a ClassCastException (500)
        if (tokenResponse == null || !(tokenResponse.get("access_token") instanceof String accessToken)
                || !StringUtils.hasText(accessToken)) {
            throw ApiException.unauthorized(MessageKey.AUTH_LINE_TOKEN_EXCHANGE_FAILED);
        }

        Map<String, Object> lineProfile = callLine(() -> restClient.get().uri(lineProfileUrl)
                .header("Authorization", "Bearer " + accessToken).retrieve().body(Map.class),
                MessageKey.AUTH_LINE_PROFILE_FAILED);

        // A blank userId would synthesize the same line_@line.oauth email for everyone
        if (lineProfile == null || !(lineProfile.get("userId") instanceof String userId) || !StringUtils.hasText(userId)) {
            throw ApiException.unauthorized(MessageKey.AUTH_LINE_PROFILE_FAILED);
        }

        // An id_token means LINE holds an email for this user, so the login needs a trusted verify result: falling back
        // to the synthetic email below would sign a user who already has an account into a second, separate one.
        // The token exchange just succeeded with this channel, so a 4xx here is a rejected token, not a configuration problem.
        String email = null;
        Object idTokenValue = tokenResponse.get("id_token");
        if (idTokenValue != null) {
            if (!(idTokenValue instanceof String idToken) || !StringUtils.hasText(idToken)) {
                log.warn("LINE token response carries an unusable id_token");
                throw ApiException.unauthorized(MessageKey.AUTH_LINE_TOKEN_EXCHANGE_FAILED);
            }
            MultiValueMap<String, String> verifyBody = new LinkedMultiValueMap<>();
            verifyBody.add("id_token", idToken);
            verifyBody.add("client_id", lineChannelId);

            Map<String, Object> verifyResponse = callLine(() -> postForm(lineVerifyUrl, verifyBody),
                    MessageKey.AUTH_LINE_TOKEN_EXCHANGE_FAILED);
            Object emailValue = verifyResponse == null ? null : verifyResponse.get("email");
            if (verifyResponse == null || !(verifyResponse.get("sub") instanceof String sub)
                    || (emailValue != null && !(emailValue instanceof String))) {
                log.warn("LINE id_token verify returned an empty or malformed body");
                throw ApiException.serviceUnavailable(MessageKey.COMMON_SERVICE_UNAVAILABLE);
            }
            if (!sub.equals(userId)) {
                log.warn("LINE id_token belongs to another user than the profile: sub={}, userId={}", sub, userId);
                throw ApiException.unauthorized(MessageKey.AUTH_LINE_TOKEN_EXCHANGE_FAILED);
            }
            // Only a verified email is returned; none means the user granted no email scope
            if (emailValue instanceof String verifiedEmail && !verifiedEmail.isBlank()) {
                email = verifiedEmail;
            } else {
                log.info("LINE login uses a synthetic email: userId={}, reason=verify returned no email", userId);
            }
        } else {
            log.info("LINE login uses a synthetic email: userId={}, reason=no id_token", userId);
        }

        if (email == null) {
            email = "line_" + userId + "@line.oauth";
        }

        String displayName = lineProfile.get("displayName") instanceof String name ? name : "";

        // LINE's verify endpoint only returns the email when it is verified; otherwise the synthesized
        // line_<userId>@line.oauth is unique and cannot be set by an attacker to a victim's real email -> both cases are safely treated as verified
        FoundUser found = findOrCreateUserByEmail(User.normalizeEmail(email), displayName, "line", true);
        return buildAuthResponse(found.user(), false, found.created());
    }

    /**
     * Runs a call to LINE: a 4xx means LINE rejected the code or token -> 401 {@code rejected}; a 429 / 408 is
     * transient on LINE's side and, like any other failure, goes through {@link #callUpstream} -> 503.
     */
    private static <T> T callLine(Supplier<T> call, MessageKey rejected) {
        return callUpstream(LINE, () -> {
            try {
                return call.get();
            } catch (HttpClientErrorException ex) {
                int status = ex.getStatusCode().value();
                if (status == 429 || status == 408) {
                    throw ex;
                }
                // The response body goes to DEBUG only; WARN keeps the status
                log.atDebug().setMessage("{} answered {}: {}").addArgument(LINE).addArgument(status)
                        .addArgument(ex::getResponseBodyAsString).log();
                log.warn("{} rejected the request ({}): {} {}", LINE, rejected, status, ex.getStatusText());
                throw ApiException.unauthorized(rejected);
            }
        });
    }

    /**
     * Runs a call to an external provider where any failure, a 4xx included, is upstream and retryable: a
     * {@link RestClientException} -> 503. Spring's exception message carries the response body, so WARN logs only
     * the exception type and status and the full message goes to DEBUG.
     */
    private static <T> T callUpstream(String upstream, Supplier<T> call) {
        try {
            return call.get();
        } catch (RestClientException ex) {
            String status = ex instanceof RestClientResponseException response ? " " + response.getStatusCode().value() : "";
            log.warn("{} call failed ({}{})", upstream, ex.getClass().getSimpleName(), status);
            log.atDebug().setMessage("{} call failure: {}").addArgument(upstream).addArgument(ex::getMessage).log();
            throw ApiException.serviceUnavailable(MessageKey.COMMON_SERVICE_UNAVAILABLE);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> postForm(String url, MultiValueMap<String, String> form) {
        return restClient.post().uri(url)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(Map.class);
    }

    // ── Apple OAuth ───────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public AuthResponse appleLogin(String idToken, String fullName, String nonce) {
        // Without a configured client id the audience cannot be checked, so a token minted for any app would pass
        if (!StringUtils.hasText(appleClientId)) {
            throw ApiException.badRequest(MessageKey.AUTH_APPLE_NOT_CONFIGURED);
        }
        // P6-#16: JWKS fetching (external call + null guard + 5xx/connection failure classification) stays outside the try,
        // so a 503 external error from an Apple/network failure is not swallowed by the broad catch below and misreported as a 401 login failure.
        List<Map<String, Object>> keys = fetchJwks(appleJwksUrl, "Apple", false);

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

            // The token's nonce claim is SHA-256(raw nonce) sent to Apple by this very sign-in; a token captured from
            // another session or app carries a different one. Checked before the token is consumed, so a mismatch
            // burns nothing.
            if (!(payload.get("nonce") instanceof String n) || !n.equalsIgnoreCase(AppKey.sha256Hex(nonce))) {
                throw ApiException.unauthorized(MessageKey.AUTH_APPLE_INVALID_NONCE);
            }
            consumeIdToken(idToken, payload.get("exp"));

            String email = (String) payload.get("email");
            if (email == null)
                throw ApiException.unauthorized(MessageKey.AUTH_APPLE_NO_EMAIL);

            // Apple id_token usually returns email_verified as the string "true", occasionally as boolean true; parse defensively
            boolean emailVerified = "true".equals(String.valueOf(payload.get("email_verified")));

            FoundUser found = findOrCreateUserByEmail(User.normalizeEmail(email), fullName, "apple", emailVerified);
            return buildAuthResponse(found.user(), false, found.created());

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
    private Map<String, Object> verifyRs256(String idToken, List<Map<String, Object>> keys,
            String jwksUrl, String provider,
            Supplier<ApiException> invalidToken, Supplier<ApiException> keyNotFound,
            Supplier<ApiException> invalidSignature) throws Exception {
        Base64.Decoder base64 = Base64.getUrlDecoder();
        String[] parts = idToken.split("\\.");
        if (parts.length != 3)
            throw invalidToken.get();

        var headerMap = JSON_MAPPER.readValue(base64.decode(parts[0]), Map.class);
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

        var rsaKey = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(
                new BigInteger(1, base64.decode((String) matchingKey.get("n"))),
                new BigInteger(1, base64.decode((String) matchingKey.get("e")))));

        var sig = Signature.getInstance("SHA256withRSA");
        sig.initVerify(rsaKey);
        sig.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
        if (!sig.verify(base64.decode(parts[2])))
            throw invalidSignature.get();

        return JSON_MAPPER.readValue(base64.decode(parts[1]), Map.class);
    }

    /**
     * Marks a verified id_token as used, refusing it the second time: a token captured in transit or pasted elsewhere
     * signs in at most once. The marker lives as long as the token is still accepted (its exp plus the clock skew),
     * so it cannot expire before the token does. Keyed by the token's hash so Redis never holds the token itself.
     *
     * @param exp the token's exp claim, already checked by {@link #isExpired}
     */
    private void consumeIdToken(String idToken, Object exp) {
        Instant acceptedUntil = Instant.ofEpochSecond(((Number) exp).longValue()).plus(ID_TOKEN_CLOCK_SKEW);
        Duration ttl = Duration.between(Instant.now(), acceptedUntil);
        if (ttl.compareTo(Duration.ofSeconds(1)) < 0) {
            ttl = Duration.ofSeconds(1);
        }
        if (!redisService.setIfAbsent(OAUTH_USED_NS, AppKey.sha256Hex(idToken), "1", ttl)) {
            throw ApiException.unauthorized(MessageKey.AUTH_OAUTH_TOKEN_REPLAYED);
        }
    }

    private static Map<String, Object> findKey(List<Map<String, Object>> keys, String kid) {
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
     *   <li><b>External failure classification</b>: any failure talking to the JWKS endpoint (every
     *       {@link RestClientException}: a 4xx or 5xx response, a connection failure or timeout) -> throw 503
     *       {@link MessageKey#COMMON_SERVICE_UNAVAILABLE}, <b>not</b> misreported as a 401 login failure; the request
     *       carries no user input, so even a 4xx is the provider's problem.</li>
     *   <li><b>Null guard</b>: a null response body, or null or empty {@code keys} -> treated as an abnormal upstream response -> 503.</li>
     * </ul>
     *
     * @param refresh bypass a fresh cache (kid miss); still served from cache within {@link #JWKS_REFETCH_COOLDOWN}
     * @return the parsed, non-empty public key list
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchJwks(String url, String provider, boolean refresh) {
        JwksCache cached = jwksCache.get(url);
        if (cached != null && cached.isYoungerThan(refresh ? JWKS_REFETCH_COOLDOWN : JWKS_CACHE_TTL)) {
            return cached.keys();
        }

        Map<String, Object> jwks = callUpstream(provider + " JWKS", () -> restClient.get().uri(url).retrieve().body(Map.class));

        if (jwks == null || jwks.get("keys") == null) {
            log.warn("Abnormal {} JWKS response: body or keys is null", provider);
            throw ApiException.serviceUnavailable(MessageKey.COMMON_SERVICE_UNAVAILABLE);
        }

        List<Map<String, Object>> keys = (List<Map<String, Object>>) jwks.get("keys");
        if (keys.isEmpty()) {
            log.warn("Abnormal {} JWKS response: keys is empty", provider);
            throw ApiException.serviceUnavailable(MessageKey.COMMON_SERVICE_UNAVAILABLE);
        }

        jwksCache.put(url, new JwksCache(keys, Instant.now()));
        return keys;
    }

    // ── Shared ────────────────────────────────────────────────────────

    /** A find-or-create outcome: {@code created} is true when this call made the account (the sign-in is a signup). */
    record FoundUser(User user, boolean created) {}

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
     * <p>This is the only place an email is proven to be the user's, so pending email Invitees and Invite List members of it are
     * tied to the user here, in the same transaction as the find-or-create (Polls locked before Invite Lists); rerunning it binds nothing new.</p>
     */
    FoundUser findOrCreateUserByEmail(String email, String fullName, String authMethod, boolean emailVerified) {
        if (!emailVerified) {
            // Email not verified by the provider -> refuse to merge or create, preventing account takeover
            throw ApiException.unauthorized(MessageKey.AUTH_EMAIL_NOT_VERIFIED);
        }
        // Programmatic transaction: callers are in this class, so @Transactional here would be skipped
        return transactionTemplate.execute(status -> {
            boolean[] created = { false };
            User user = userRepository.findByEmail(email).orElseGet(() -> {
                String localPart = email.split("@")[0];
                User newUser = User.builder()
                        .email(email)
                        .username(generateUsername(localPart))
                        .displayName(StringUtils.hasText(fullName) ? fullName : localPart)
                        .authMethod(authMethod)
                        .avatarUrl(BotAvatar.random())
                        .build();
                created[0] = true;
                return userRepository.save(newUser);
            });
            voteService.bindEmailInvitees(user);
            inviteListService.bindEmailMembers(user);
            return new FoundUser(user, created[0]);
        });
    }

    FoundUser findOrCreateUserByPhone(String phone) {
        String normalizedPhone = User.normalizePhone(phone);
        return userRepository.findByPhoneNumber(normalizedPhone).map(u -> new FoundUser(u, false)).orElseGet(() -> {
            User newUser = User.builder()
                    .phoneNumber(normalizedPhone)
                    .username(generateUsername("user"))
                    .displayName(normalizedPhone)
                    .authMethod("phone_otp")
                    .avatarUrl(BotAvatar.random())
                    .build();
            return new FoundUser(userRepository.save(newUser), true);
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

    /** @param isNewUser whether the account was created by this very sign-in (reported to the client, see AuthResponse) */
    AuthResponse buildAuthResponse(User user, boolean adminScope, boolean isNewUser) {
        // Ban check: all login paths (OTP/Google/LINE/Apple, phone) funnel through here, so they are blocked in one place
        if (!user.isActive()) {
            throw ApiException.forbidden(MessageKey.AUTH_ACCOUNT_BANNED);
        }
        user.setLastLoginAt(OffsetDateTime.now());
        userRepository.save(user);
        List<String> roles = List.of(user.getRoles());
        RefreshTokenService.IssuedTokens tokens = refreshTokenService.issue(user.getId().toString(), roles, adminScope);
        return new AuthResponse(tokens.accessToken(), tokens.refreshToken(),
                new AuthResponse.UserInfo(user.getId().toString(), user.getEmail(), user.getUsername(),
                        user.getDisplayName(), roles, user.getAvatarUrl(), user.getPhoneNumber()), isNewUser);
    }

    public AuthResponse refresh(String refreshToken) {
        try {
            RefreshTokenService.RotateResult result = refreshTokenService.rotate(refreshToken);
            if (result == null) {
                throw ApiException.unauthorized(MessageKey.AUTH_REFRESH_TOKEN_INVALID);
            }
            return new AuthResponse(result.newAccessToken(), result.newRefreshToken(), null, null);
        } catch (RefreshTokenReuseException e) {
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
