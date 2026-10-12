package com.vomatt.auth;

import com.vomatt.auth.dto.AuthResponse;
import com.vomatt.common.constant.UserRole;
import com.vomatt.common.email.EmailService;
import com.vomatt.common.exception.ApiException;
import com.vomatt.common.redis.RedisService;
import com.vomatt.common.security.JwtUtil;
import com.vomatt.common.security.RefreshTokenService;
import com.vomatt.common.sms.SmsService;
import com.vomatt.entity.User;
import com.vomatt.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

    // P6-#21: short-TTL in-memory cache for Apple JWKS, so each Apple login does not hit the JWKS endpoint.
    // Self-contained in AuthService (no Redis); implemented as a volatile holder + timestamp. With mostly
    // reads and rare writes, an occasional duplicate fetch is acceptable (no lock needed).
    private static final Duration APPLE_JWKS_CACHE_TTL = Duration.ofMinutes(10);

    /** Apple JWKS cache holder: keys is the parsed public key list, fetchedAt is the fetch time. */
    private record AppleJwksCache(List<java.util.Map<String, Object>> keys, Instant fetchedAt) {
        boolean isFresh() {
            return Duration.between(fetchedAt, Instant.now()).compareTo(APPLE_JWKS_CACHE_TTL) < 0;
        }
    }

    private volatile AppleJwksCache appleJwksCache;

    private final UserRepository userRepository;
    private final RedisService redisService;
    private final EmailService emailService;
    private final SmsService smsService;
    private final JwtUtil jwtUtil;
    private final RestClient restClient;
    private final RefreshTokenService refreshTokenService;

    @Value("${google.client-id:}")
    private String googleClientId;

    @Value("${oauth.google.token-info-url}")
    private String googleTokenInfoUrl;

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
            String normalizedEmail = email.toLowerCase().trim();
            if (checkRole) {
                userRepository.findByEmail(normalizedEmail)
                        .filter(u -> List.of(u.getRoles()).contains(UserRole.ADMIN))
                        .orElseThrow(() -> ApiException.forbidden(MessageKey.AUTH_ADMIN_FORBIDDEN));
            }
            sendEmailOtp(normalizedEmail, code);
        } else if (phone != null && !phone.isBlank()) {
            sendPhoneOtp(phone.trim(), otpType, code);
        } else {
            throw ApiException.badRequest(MessageKey.AUTH_IDENTIFIER_REQUIRED);
        }
    }

    private void sendEmailOtp(String normalizedEmail, String code) {
        checkResendCooldown(OTP_EMAIL_NS, normalizedEmail);

        boolean isExisting = userRepository.existsByEmail(normalizedEmail);
        String otpType = isExisting ? "login" : "signup";

        redisService.setOrThrow(OTP_EMAIL_NS, normalizedEmail, new OtpRecord(code, otpType, Instant.now(), 0), Duration.ofSeconds(otpTtlSeconds));

        try {
            if ("signup".equals(otpType)) {
                emailService.sendPreSignupEmail(normalizedEmail, code);
            } else {
                emailService.sendVerificationEmail(normalizedEmail, code);
            }
        } catch (Exception ex) {
            throw ApiException.badRequest(MessageKey.AUTH_EMAIL_SEND_FAILED, ex.getMessage());
        }
    }

    private void sendPhoneOtp(String phone, String otpType, String code) {
        checkResendCooldown(OTP_PHONE_NS, phone);

        if ("login".equals(otpType) && !userRepository.existsByPhoneNumber(phone)) {
            throw ApiException.badRequest(MessageKey.AUTH_PHONE_NOT_REGISTERED);
        }

        redisService.setOrThrow(OTP_PHONE_NS, phone, new OtpRecord(code, otpType, Instant.now(), 0), Duration.ofSeconds(otpTtlSeconds));

        try {
            smsService.sendOtp(phone, code);
        } catch (Exception ex) {
            throw ApiException.badRequest(MessageKey.AUTH_SMS_SEND_FAILED, ex.getMessage());
        }
    }

    public AuthResponse verifyOtp(String email, String phone, String code, boolean checkRole) {
        if (email != null && !email.isBlank()) {
            String normalizedEmail = email.toLowerCase().trim();
            // P5-#13: the admin login role check must run before "consume OTP / create account / send welcome email".
            // Otherwise a non-admin attempt would burn the OTP, auto-create an account and send an email before returning 403.
            if (checkRole) {
                requireExistingAdminByEmail(normalizedEmail);
            }
            return verifyEmailOtp(normalizedEmail, code);
        } else if (phone != null && !phone.isBlank()) {
            String normalizedPhone = phone.trim();
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

        if ("signup".equals(otp.type())) {
            // Sent in the background so SMTP latency does not delay the login response
            emailService.sendWelcomeEmail(normalizedEmail, user.getDisplayName(), LocaleContextHolder.getLocale());
        }

        return buildAuthResponse(user);
    }

    private AuthResponse verifyPhoneOtp(String phone, String code) {
        consumeOtp(OTP_PHONE_NS, phone, code);
        User user = findOrCreateUserByPhone(phone);
        return buildAuthResponse(user);
    }

    private static final int MAX_OTP_ATTEMPTS = 5;

    /** Namespace suffix for the failure counter; same key as the OTP, atomic INCR (P1-#7). */
    private static final String OTP_FAIL_SUFFIX = ":fail";

    private OtpRecord consumeOtp(String namespace, String key, String code) {
        OtpRecord rec = redisService.get(namespace, key, OtpRecord.class);

        if (rec == null) {
            throw ApiException.unauthorized(MessageKey.AUTH_OTP_INVALID_OR_EXPIRED);
        }

        // P5-#19: constant-time comparison so a short-circuit does not leak the OTP prefix via a timing side-channel.
        // MessageDigest.isEqual compares every byte without early exit when lengths match; it exits early on a length mismatch, but the OTP is always 6 digits.
        boolean codeMatches = MessageDigest.isEqual(
                rec.code().getBytes(StandardCharsets.UTF_8),
                code.getBytes(StandardCharsets.UTF_8));
        if (!codeMatches) {
            // P1-#7: failures are counted with a separate atomic INCR counter, so concurrent "get -> compare -> set" cannot overwrite each other and bypass the limit.
            // TTL matches the OTP's remaining lifetime (at least 1 second), so the counter expires with the OTP.
            String failNs = namespace + OTP_FAIL_SUFFIX;
            long remainingSeconds = otpTtlSeconds - Duration.between(rec.createdAt(), Instant.now()).getSeconds();
            long fails = redisService.incrementAndExpire(failNs, key, Duration.ofSeconds(Math.max(remainingSeconds, 1)));
            if (fails >= MAX_OTP_ATTEMPTS) {
                // Limit reached: delete the OTP and counter so all later attempts fail (including the correct code)
                redisService.delete(namespace, key);
                redisService.delete(failNs, key);
                throw ApiException.unauthorized(MessageKey.AUTH_OTP_TOO_MANY_ATTEMPTS);
            }
            throw ApiException.unauthorized(MessageKey.AUTH_INVALID_OTP);
        }

        redisService.delete(namespace, key);
        redisService.delete(namespace + OTP_FAIL_SUFFIX, key);
        return rec;
    }

    private void checkResendCooldown(String namespace, String key) {
        OtpRecord existing = redisService.get(namespace, key, OtpRecord.class);
        if (existing == null) return;

        long remaining = otpResendCooldownSeconds - Duration.between(existing.createdAt(), Instant.now()).getSeconds();
        if (remaining > 0) {
            throw ApiException.badRequest(MessageKey.AUTH_OTP_RESEND_COOLDOWN, remaining);
        }
    }

    public boolean checkEmailExists(String email) {
        return userRepository.existsByEmail(email.toLowerCase().trim());
    }

    // ── Google OAuth ──────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public AuthResponse googleLogin(String idToken) {
        if (googleClientId == null || googleClientId.isBlank()) {
            throw ApiException.badRequest(MessageKey.AUTH_GOOGLE_NOT_CONFIGURED);
        }

        var response = restClient.get().uri(googleTokenInfoUrl + "?id_token=" + idToken).retrieve()
                .body(java.util.Map.class);

        if (response == null || response.get("email") == null) {
            throw ApiException.unauthorized(MessageKey.AUTH_GOOGLE_NO_EMAIL);
        }

        String aud = (String) response.get("aud");
        if (!googleClientId.equals(aud)) {
            throw ApiException.unauthorized(MessageKey.AUTH_GOOGLE_INVALID_AUDIENCE);
        }

        String email = (String) response.get("email");
        String name = (String) response.getOrDefault("name", "");
        String picture = (String) response.get("picture");

        // Google tokeninfo usually returns email_verified as the string "true"/"false", occasionally as a boolean; parse defensively
        boolean emailVerified = "true".equals(String.valueOf(response.get("email_verified")));

        User user = findOrCreateUserByEmail(email.toLowerCase().trim(), name, "google", emailVerified);
        if (picture != null && user.getAvatarUrl() == null) {
            user.setAvatarUrl(picture);
            userRepository.save(user);
        }
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
        String pictureUrl = (String) lineProfile.get("pictureUrl");

        // LINE's verify endpoint only returns the email when it is verified; otherwise the synthesized
        // line_<userId>@line.oauth is unique and cannot be set by an attacker to a victim's real email -> both cases are safely treated as verified
        User user = findOrCreateUserByEmail(email.toLowerCase().trim(), displayName, "line", true);
        if (pictureUrl != null && user.getAvatarUrl() == null) {
            user.setAvatarUrl(pictureUrl);
            userRepository.save(user);
        }
        return buildAuthResponse(user);
    }

    // ── Apple OAuth ───────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public AuthResponse appleLogin(String idToken, String fullName) {
        // P6-#16: JWKS fetching (external call + null guard + 5xx/connection failure classification) stays outside the try,
        // so a 503 external error from an Apple/network failure is not swallowed by the broad catch below and misreported as a 401 login failure.
        List<java.util.Map<String, Object>> keys = fetchAppleJwks();

        try {
            String[] parts = idToken.split("\\.");
            if (parts.length != 3)
                throw ApiException.unauthorized(MessageKey.AUTH_APPLE_INVALID_TOKEN);

            byte[] headerBytes = java.util.Base64.getUrlDecoder().decode(parts[0]);
            var headerMap = JSON_MAPPER.readValue(headerBytes, java.util.Map.class);
            String kid = (String) headerMap.get("kid");
            // P6-#16: no kid in the header -> cannot locate the public key; invalid token (credential problem) -> 401.
            if (kid == null)
                throw ApiException.unauthorized(MessageKey.AUTH_APPLE_INVALID_TOKEN);

            var matchingKey = keys.stream().filter(k -> kid.equals(k.get("kid"))).findFirst()
                    .orElseThrow(() -> ApiException.unauthorized(MessageKey.AUTH_APPLE_KEY_NOT_FOUND));

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
                throw ApiException.unauthorized(MessageKey.AUTH_APPLE_INVALID_SIGNATURE);

            byte[] payloadBytes = java.util.Base64.getUrlDecoder().decode(parts[1]);
            var payload = JSON_MAPPER.readValue(payloadBytes, java.util.Map.class);

            if (!"https://appleid.apple.com".equals(payload.get("iss"))) {
                throw ApiException.unauthorized(MessageKey.AUTH_APPLE_INVALID_ISSUER);
            }
            if (appleClientId != null && !appleClientId.isBlank() && !appleClientId.equals(payload.get("aud"))) {
                throw ApiException.unauthorized(MessageKey.AUTH_APPLE_INVALID_AUDIENCE);
            }
            Number exp = (Number) payload.get("exp");
            if (exp != null && exp.longValue() * 1000 < System.currentTimeMillis()) {
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

            User user = findOrCreateUserByEmail(email.toLowerCase().trim(),
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
     * Fetches and caches Apple's public key list (JWKS).
     *
     * <p>P6-#16 / P6-#21:</p>
     * <ul>
     *   <li><b>Cache</b>: returns directly on a fresh hit (10-minute TTL), so each Apple login does not hit the JWKS endpoint.</li>
     *   <li><b>External failure classification</b>: 5xx / connection failures talking to the Apple JWKS endpoint (all
     *       {@link RestClientException} subclasses, including {@code HttpServerErrorException}, {@code ResourceAccessException})
     *       -> throw 503 {@link MessageKey#COMMON_SERVICE_UNAVAILABLE}, <b>not</b> misreported as a 401 login failure.</li>
     *   <li><b>Null guard</b>: a null response body, or null or empty {@code keys} -> treated as an abnormal upstream response -> 503.</li>
     * </ul>
     *
     * @return the parsed, non-empty public key list
     */
    @SuppressWarnings("unchecked")
    private List<java.util.Map<String, Object>> fetchAppleJwks() {
        AppleJwksCache cached = appleJwksCache;
        if (cached != null && cached.isFresh()) {
            return cached.keys();
        }

        java.util.Map<String, Object> jwks;
        try {
            jwks = restClient.get().uri(appleJwksUrl).retrieve().body(java.util.Map.class);
        } catch (RestClientException ex) {
            // Apple JWKS endpoint 5xx / connection timeout / DNS failure -> upstream/external service failure, retryable (503), not a credential problem
            log.warn("Failed to fetch Apple JWKS (external service): {}", ex.getMessage());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, MessageKey.COMMON_SERVICE_UNAVAILABLE);
        }

        if (jwks == null || jwks.get("keys") == null) {
            log.warn("Abnormal Apple JWKS response: body or keys is null");
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, MessageKey.COMMON_SERVICE_UNAVAILABLE);
        }

        List<java.util.Map<String, Object>> keys = (List<java.util.Map<String, Object>>) jwks.get("keys");
        if (keys.isEmpty()) {
            log.warn("Abnormal Apple JWKS response: keys is empty");
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, MessageKey.COMMON_SERVICE_UNAVAILABLE);
        }

        appleJwksCache = new AppleJwksCache(keys, Instant.now());
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
     */
    @Transactional
    User findOrCreateUserByEmail(String email, String fullName, String authMethod, boolean emailVerified) {
        if (!emailVerified) {
            // Email not verified by the provider -> refuse to merge or create, preventing account takeover
            throw ApiException.unauthorized(MessageKey.AUTH_EMAIL_NOT_VERIFIED);
        }
        return userRepository.findByEmail(email).orElseGet(() -> {
            User newUser = User.builder()
                    .email(email)
                    .username(generateUsername(email.split("@")[0]))
                    .displayName((fullName != null && !fullName.isBlank()) ? fullName : email.split("@")[0])
                    .authMethod(authMethod)
                    .build();
            return userRepository.save(newUser);
        });
    }

    @Transactional
    User findOrCreateUserByPhone(String phone) {
        return userRepository.findByPhoneNumber(phone).orElseGet(() -> {
            User newUser = User.builder()
                    .phoneNumber(phone)
                    .username(generateUsername("user"))
                    .displayName(phone)
                    .authMethod("phone_otp")
                    .build();
            return userRepository.save(newUser);
        });
    }

    /**
     * Generates a unique username from the email local part (or a fixed prefix): keeps only [a-z0-9_], at most 40 chars, plus a 4-digit random suffix to avoid collisions.
     */
    private String generateUsername(String seed) {
        String base = seed.toLowerCase().replaceAll("[^a-z0-9_]", "");
        if (base.isBlank()) base = "user";
        if (base.length() > 40) base = base.substring(0, 40);
        String candidate;
        do {
            candidate = base + "_" + "%04d".formatted(RANDOM.nextInt(10000));
        } while (userRepository.existsByUsername(candidate));
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
