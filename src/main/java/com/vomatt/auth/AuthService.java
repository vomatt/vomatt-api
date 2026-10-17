package com.vomatt.auth;

import com.vomatt.auth.dto.AuthResponse;
import com.vomatt.common.constant.ReservedUsernames;
import com.vomatt.common.constant.UserRole;
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
import java.util.List;
import java.util.UUID;
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

    @Value("${otp.ttl-seconds:180}")
    private long otpTtlSeconds;

    @Value("${otp.resend-cooldown-seconds:180}")
    private long otpResendCooldownSeconds;

    public void sendOtp(String email, String phone, String type, boolean checkRole) {
        String otpType = (type != null) ? type : "login";
        String code = generateOtpCode();

        if (email != null && !email.isBlank()) {
            String normalizedEmail = User.normalizeEmail(email);
            if (checkRole) {
                userRepository.findByEmail(normalizedEmail)
                        .filter(u -> List.of(u.getRoles()).contains(UserRole.ADMIN))
                        .orElseThrow(() -> ApiException.forbidden(MessageKey.AUTH_ADMIN_FORBIDDEN));
            }
            sendEmailOtp(normalizedEmail, code);
        } else if (phone != null && !phone.isBlank()) {
            sendPhoneOtp(User.normalizePhone(phone), otpType, code);
        } else {
            throw ApiException.badRequest(MessageKey.AUTH_IDENTIFIER_REQUIRED);
        }
    }

    private void sendEmailOtp(String normalizedEmail, String code) {
        rejectIfLocked(OTP_EMAIL_NS, normalizedEmail);

        boolean isExisting = userRepository.existsByEmail(normalizedEmail);
        String otpType = isExisting ? "login" : "signup";

        acquireResendCooldown(OTP_EMAIL_NS, normalizedEmail);
        storeOtp(OTP_EMAIL_NS, normalizedEmail, new OtpRecord(code, otpType, Instant.now(), 0));

        try {
            if ("signup".equals(otpType)) {
                emailService.sendPreSignupEmail(normalizedEmail, code);
            } else {
                emailService.sendVerificationEmail(normalizedEmail, code);
            }
        } catch (Exception ex) {
            // The code never reached the user: drop it and shorten the cooldown so a retry is possible soon
            releaseUndeliveredOtp(OTP_EMAIL_NS, normalizedEmail);
            log.warn("Failed to send OTP email: {}", ex.getMessage());
            throw ApiException.badRequest(MessageKey.AUTH_EMAIL_SEND_FAILED);
        }
    }

    private void sendPhoneOtp(String phone, String otpType, String code) {
        rejectIfLocked(OTP_PHONE_NS, phone);

        if ("login".equals(otpType) && !userRepository.existsByPhoneNumber(phone)) {
            throw ApiException.badRequest(MessageKey.AUTH_PHONE_NOT_REGISTERED);
        }

        acquireResendCooldown(OTP_PHONE_NS, phone);
        storeOtp(OTP_PHONE_NS, phone, new OtpRecord(code, otpType, Instant.now(), 0));

        try {
            smsService.sendOtp(phone, code);
        } catch (Exception ex) {
            releaseUndeliveredOtp(OTP_PHONE_NS, phone);
            log.warn("Failed to send OTP SMS: {}", ex.getMessage());
            throw ApiException.badRequest(MessageKey.AUTH_SMS_SEND_FAILED);
        }
    }

    public AuthResponse verifyOtp(String email, String phone, String code, boolean checkRole) {
        if (email != null && !email.isBlank()) {
            String normalizedEmail = User.normalizeEmail(email);
            // P5-#13: the admin login role check must run before "consume OTP / create account / send welcome email".
            // Otherwise a non-admin attempt would burn the OTP, auto-create an account and send an email before returning 403.
            if (checkRole) {
                requireExistingAdminByEmail(normalizedEmail);
            }
            return verifyEmailOtp(normalizedEmail, code);
        } else if (phone != null && !phone.isBlank()) {
            String normalizedPhone = User.normalizePhone(phone);
            // P5-#13: the phone path must also check the role before consuming the OTP (both paths covered).
            if (checkRole) {
                requireExistingAdminByPhone(normalizedPhone);
            }
            return verifyPhoneOtp(normalizedPhone, code);
        } else {
            throw ApiException.badRequest(MessageKey.AUTH_IDENTIFIER_REQUIRED);
        }
    }

    /**
     * P5-#13: requires an existing account for the email with the ADMIN role; otherwise throws 403.
     * Called before {@link #consumeOtp} so a rejected admin attempt does not consume the OTP, create an account or send email.
     */
    private void requireExistingAdminByEmail(String normalizedEmail) {
        userRepository.findByEmail(normalizedEmail)
                .filter(u -> List.of(u.getRoles()).contains(UserRole.ADMIN))
                .orElseThrow(() -> ApiException.forbidden(MessageKey.AUTH_ADMIN_FORBIDDEN));
    }

    /** P5-#13: phone variant of the existing ADMIN account check; same semantics as {@link #requireExistingAdminByEmail}. */
    private void requireExistingAdminByPhone(String normalizedPhone) {
        userRepository.findByPhoneNumber(normalizedPhone)
                .filter(u -> List.of(u.getRoles()).contains(UserRole.ADMIN))
                .orElseThrow(() -> ApiException.forbidden(MessageKey.AUTH_ADMIN_FORBIDDEN));
    }

    private AuthResponse verifyEmailOtp(String normalizedEmail, String code) {
        OtpRecord otp = consumeOtp(OTP_EMAIL_NS, normalizedEmail, code);

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

    private AuthResponse verifyPhoneOtp(String phone, String code) {
        consumeOtp(OTP_PHONE_NS, phone, code);
        User user = findOrCreateUserByPhone(phone);
        return buildAuthResponse(user);
    }

    private static final int MAX_OTP_ATTEMPTS = 5;

    /** Namespace suffix for the failure counter; same key as the OTP, atomic INCR (P1-#7). */
    private static final String OTP_FAIL_SUFFIX = ":fail";

    /** Namespace suffix for the lockout marker; independent of the OTP so it survives OTP deletion and resends. */
    private static final String OTP_LOCK_SUFFIX = ":lock";

    /** Namespace suffix for the resend cooldown marker (SET NX), holding the epoch second a resend is allowed again. */
    private static final String OTP_COOLDOWN_SUFFIX = ":cooldown";

    private static final Duration OTP_LOCK_TTL = Duration.ofMinutes(15);

    /** Cooldown kept after a failed delivery, so a script cannot hammer the paid SMS / email provider. */
    private static final Duration OTP_FAILED_SEND_COOLDOWN = Duration.ofSeconds(30);

    private OtpRecord consumeOtp(String namespace, String key, String code) {
        rejectIfLocked(namespace, key);

        OtpRecord rec = redisService.get(namespace, key, OtpRecord.class);

        if (rec == null) {
            throw ApiException.unauthorized(MessageKey.AUTH_OTP_INVALID_OR_EXPIRED);
        }

        // Every attempt is counted with an atomic INCR BEFORE the code is compared, so concurrent guesses cannot
        // all slip past the limit (P1-#7). TTL matches the OTP's remaining lifetime (at least 1 second).
        String failNs = namespace + OTP_FAIL_SUFFIX;
        long remainingSeconds = otpTtlSeconds - Duration.between(rec.createdAt(), Instant.now()).getSeconds();
        long attempts = redisService.incrementAndExpire(failNs, key, Duration.ofSeconds(Math.max(remainingSeconds, 1)));
        // Past the limit: a concurrent guess already used the last attempt, so even a correct code is refused
        if (attempts > MAX_OTP_ATTEMPTS) {
            lockOut(namespace, key);
        }

        // P5-#19: constant-time comparison so a short-circuit does not leak the OTP prefix via a timing side-channel.
        // MessageDigest.isEqual compares every byte without early exit when lengths match; it exits early on a length mismatch, but the OTP is always 6 digits.
        boolean codeMatches = MessageDigest.isEqual(
                rec.code().getBytes(StandardCharsets.UTF_8),
                code.getBytes(StandardCharsets.UTF_8));
        if (!codeMatches) {
            // The last allowed attempt was wrong: lock now rather than on the next request
            if (attempts == MAX_OTP_ATTEMPTS) {
                lockOut(namespace, key);
            }
            throw ApiException.unauthorized(MessageKey.AUTH_INVALID_OTP);
        }

        redisService.delete(namespace, key);
        redisService.delete(failNs, key);
        return rec;
    }

    /**
     * Writes the lockout marker (15 minutes, outlives the OTP so a resend does not grant fresh guesses),
     * invalidates the OTP and counter, then rejects.
     */
    private void lockOut(String namespace, String key) {
        redisService.setOrThrow(namespace + OTP_LOCK_SUFFIX, key, "1", OTP_LOCK_TTL);
        redisService.delete(namespace, key);
        redisService.delete(namespace + OTP_FAIL_SUFFIX, key);
        throw ApiException.unauthorized(MessageKey.AUTH_OTP_TOO_MANY_ATTEMPTS);
    }

    private void rejectIfLocked(String namespace, String key) {
        // Fails closed: a Redis error must not let a locked identifier guess again
        if (redisService.hasKeyOrThrow(namespace + OTP_LOCK_SUFFIX, key)) {
            throw ApiException.unauthorized(MessageKey.AUTH_OTP_TOO_MANY_ATTEMPTS);
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

    /** Stores the OTP; if that fails, releases the cooldown just claimed so the user can retry at once. */
    private void storeOtp(String namespace, String key, OtpRecord record) {
        try {
            redisService.setOrThrow(namespace, key, record, Duration.ofSeconds(otpTtlSeconds));
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
        // Ban check: all login paths (OTP/Google/LINE/Apple, phone) funnel through here, so they are blocked in one place
        if (!user.isActive()) {
            throw ApiException.forbidden(MessageKey.AUTH_ACCOUNT_BANNED);
        }
        user.setLastLoginAt(java.time.OffsetDateTime.now());
        userRepository.save(user);
        List<String> roles = List.of(user.getRoles());
        String token = jwtUtil.generateToken(user.getId().toString(), user.getEmail(), roles);
        String refreshToken = refreshTokenService.generate(user.getId().toString(), user.getEmail(), roles);
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
