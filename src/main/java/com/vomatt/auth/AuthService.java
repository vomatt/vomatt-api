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

    // P6-#21：Jackson 3 的 JsonMapper/ObjectMapper 為 thread-safe，應以單例重用
    // （每次 build 會重建 serializer/deserializer 快取，浪費資源）。Apple 登入解析
    // JWT header / payload 共用此單例。
    private static final tools.jackson.databind.json.JsonMapper JSON_MAPPER =
            tools.jackson.databind.json.JsonMapper.builder().build();

    // P6-#21：Apple JWKS 短 TTL 記憶體快取，避免每次 Apple 登入都重新打 JWKS endpoint。
    // 自包含於 AuthService（不引入 Redis）；以 volatile holder + 時間戳實作，讀多寫少場景下
    // 偶發重複抓取可接受（不需鎖）。
    private static final Duration APPLE_JWKS_CACHE_TTL = Duration.ofMinutes(10);

    /** Apple JWKS 快取 holder：keys 為已解析的公鑰清單，fetchedAt 為抓取時間。 */
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
            // P5-#13：admin 登入流程的角色檢查必須在「消耗 OTP / 建帳號 / 寄歡迎信」之前完成。
            // 否則非 admin 嘗試會先把 OTP 燒掉、自動建帳號、寄信，最後才回 403。
            if (checkRole) {
                requireExistingAdminByEmail(normalizedEmail);
            }
            return verifyEmailOtp(normalizedEmail, code);
        } else if (phone != null && !phone.isBlank()) {
            String normalizedPhone = phone.trim();
            // P5-#13：phone 路徑同樣須在消耗 OTP 前驗角色（雙路徑皆涵蓋）。
            if (checkRole) {
                requireExistingAdminByPhone(normalizedPhone);
            }
            return verifyPhoneOtp(normalizedPhone, code);
        } else {
            throw ApiException.badRequest(MessageKey.AUTH_IDENTIFIER_REQUIRED);
        }
    }

    /**
     * P5-#13：要求 email 對應的既有帳號存在且為 ADMIN，否則拋 403。
     * 在 {@link #consumeOtp} 之前呼叫，確保被拒絕的 admin 嘗試不會消耗 OTP、不會建帳號、不會寄信。
     */
    private void requireExistingAdminByEmail(String normalizedEmail) {
        userRepository.findByEmail(normalizedEmail)
                .filter(u -> List.of(u.getRoles()).contains(UserRole.ADMIN))
                .orElseThrow(() -> ApiException.forbidden(MessageKey.AUTH_ADMIN_FORBIDDEN));
    }

    /** P5-#13：phone 版的既有 ADMIN 帳號檢查，語意同 {@link #requireExistingAdminByEmail}。 */
    private void requireExistingAdminByPhone(String normalizedPhone) {
        userRepository.findByPhoneNumber(normalizedPhone)
                .filter(u -> List.of(u.getRoles()).contains(UserRole.ADMIN))
                .orElseThrow(() -> ApiException.forbidden(MessageKey.AUTH_ADMIN_FORBIDDEN));
    }

    private AuthResponse verifyEmailOtp(String normalizedEmail, String code) {
        OtpRecord otp = consumeOtp(OTP_EMAIL_NS, normalizedEmail, code);

        String defaultName = "signup".equals(otp.type()) ? normalizedEmail.split("@")[0] : "";
        // 使用者已透過 OTP 證明掌握該 email → 視為已驗證
        User user = findOrCreateUserByEmail(normalizedEmail, defaultName, "email_otp", true);

        if ("signup".equals(otp.type())) {
            try {
                emailService.sendWelcomeEmail(normalizedEmail, user.getDisplayName());
            } catch (Exception ex) {
                log.warn("歡迎信發送失敗: {}", ex.getMessage());
            }
        }

        return buildAuthResponse(user);
    }

    private AuthResponse verifyPhoneOtp(String phone, String code) {
        consumeOtp(OTP_PHONE_NS, phone, code);
        User user = findOrCreateUserByPhone(phone);
        return buildAuthResponse(user);
    }

    private static final int MAX_OTP_ATTEMPTS = 5;

    /** 失敗計數 counter 的 namespace 後綴；與 OTP 同 key、原子 INCR（P1-#7）。 */
    private static final String OTP_FAIL_SUFFIX = ":fail";

    private OtpRecord consumeOtp(String namespace, String key, String code) {
        OtpRecord rec = redisService.get(namespace, key, OtpRecord.class);

        if (rec == null) {
            throw ApiException.unauthorized(MessageKey.AUTH_OTP_INVALID_OR_EXPIRED);
        }

        // P5-#19：常數時間比對，避免以早退（short-circuit）洩漏 OTP 前綴的 timing side-channel。
        // MessageDigest.isEqual 在長度相同時逐 byte 比對且不早退；長度不同時雖會早退，但 OTP 為固定 6 碼。
        boolean codeMatches = MessageDigest.isEqual(
                rec.code().getBytes(StandardCharsets.UTF_8),
                code.getBytes(StandardCharsets.UTF_8));
        if (!codeMatches) {
            // P1-#7：失敗計數以獨立 counter 原子 INCR，避免「get→比對→set」並發互相覆蓋而繞過上限。
            // TTL 對齊 OTP 剩餘壽命（至少 1 秒），OTP 到期計數一併到期。
            String failNs = namespace + OTP_FAIL_SUFFIX;
            long remainingSeconds = otpTtlSeconds - Duration.between(rec.createdAt(), Instant.now()).getSeconds();
            long fails = redisService.incrementAndExpire(failNs, key, Duration.ofSeconds(Math.max(remainingSeconds, 1)));
            if (fails >= MAX_OTP_ATTEMPTS) {
                // 達上限：刪除 OTP 與計數，使後續嘗試一律失效（含正確碼）
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

        // Google tokeninfo 的 email_verified 多回字串 "true"/"false"，少數情況回布林；穩健解析
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

        // LINE 只在 email 已驗證時才於 verify endpoint 回傳 email；找不到時合成的
        // line_<userId>@line.oauth 為唯一、非攻擊者可指定的受害者真實 email → 兩種情況皆視為安全已驗證
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
        // P6-#16：JWKS 取得（外部呼叫 + null 防護 + 5xx/連線失敗分類）獨立於 try 之外，
        // 確保「Apple/網路故障」拋出的 503 外部錯誤不會被下方寬鬆 catch 吃掉、誤報為 401 登入失敗。
        List<java.util.Map<String, Object>> keys = fetchAppleJwks();

        try {
            String[] parts = idToken.split("\\.");
            if (parts.length != 3)
                throw ApiException.unauthorized(MessageKey.AUTH_APPLE_INVALID_TOKEN);

            byte[] headerBytes = java.util.Base64.getUrlDecoder().decode(parts[0]);
            var headerMap = JSON_MAPPER.readValue(headerBytes, java.util.Map.class);
            String kid = (String) headerMap.get("kid");
            // P6-#16：header 無 kid → 無法定位公鑰，屬無效 token（憑證問題）→ 401。
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

            // TODO(apple-nonce): 完整 anti-replay 需「後端發 nonce → 存 Redis → client 帶給 Apple
            //   → Apple 在 id_token 回傳 nonce → 後端於此比對並消費」。目前 controller/DTO 尚無 nonce
            //   發放流程，整套 nonce store 基礎設施超出本批範圍。此處先預留比對點：若 payload 含 nonce
            //   則記錄，待 client 與 nonce store 就緒後改為強制比對。本批以 email_verified 為實際堵漏重點。
            Object nonce = payload.get("nonce");
            if (nonce != null) {
                log.debug("Apple id_token 含 nonce，待 nonce store 就緒後比對（目前僅預留）");
            }

            String email = (String) payload.get("email");
            if (email == null)
                throw ApiException.unauthorized(MessageKey.AUTH_APPLE_NO_EMAIL);

            // Apple id_token 的 email_verified 多回字串 "true"，少數情況回布林 true；穩健解析
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
     * 取得並快取 Apple 公鑰清單（JWKS）。
     *
     * <p>P6-#16 / P6-#21：</p>
     * <ul>
     *   <li><b>快取</b>：命中且未過期（10 分鐘 TTL）則直接回傳，避免每次 Apple 登入都重打 JWKS endpoint。</li>
     *   <li><b>外部故障分類</b>：與 Apple JWKS endpoint 溝通時的 5xx / 連線失敗（皆為
     *       {@link RestClientException} 子類，含 {@code HttpServerErrorException}、{@code ResourceAccessException}）
     *       → 拋 503 {@link MessageKey#COMMON_SERVICE_UNAVAILABLE}，<b>不</b>誤報為 401 登入失敗。</li>
     *   <li><b>null 防護</b>：回應 body 為 null、{@code keys} 為 null 或空 → 同視為上游回應異常 → 503。</li>
     * </ul>
     *
     * @return 已解析、非空的公鑰清單
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
            // Apple JWKS endpoint 5xx / 連線逾時 / DNS 失敗 → 上游/外部服務故障，可重試（503），非憑證問題
            log.warn("Apple JWKS 取得失敗（外部服務）：{}", ex.getMessage());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, MessageKey.COMMON_SERVICE_UNAVAILABLE);
        }

        if (jwks == null || jwks.get("keys") == null) {
            log.warn("Apple JWKS 回應異常：body 或 keys 為 null");
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, MessageKey.COMMON_SERVICE_UNAVAILABLE);
        }

        List<java.util.Map<String, Object>> keys = (List<java.util.Map<String, Object>>) jwks.get("keys");
        if (keys.isEmpty()) {
            log.warn("Apple JWKS 回應異常：keys 為空");
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, MessageKey.COMMON_SERVICE_UNAVAILABLE);
        }

        appleJwksCache = new AppleJwksCache(keys, Instant.now());
        return keys;
    }

    // ── Shared ────────────────────────────────────────────────────────

    /**
     * 依 email 找出或建立 User。
     *
     * <p>{@code emailVerified} 是防帳號接管（account takeover）的關鍵閘門：</p>
     * <ul>
     *   <li><b>已驗證</b>（true）：OAuth provider 已為此 email 背書（或使用者已透過 OTP
     *       證明掌握該 email），合併到既有帳號或建立新帳號皆安全 → 維持 find-or-create。</li>
     *   <li><b>未驗證</b>（false）：攻擊者可在 OAuth provider 任意設定受害者 email，
     *       <b>不得</b>合併到既有帳號，也<b>不得</b>建立新帳號，直接拋
     *       {@link MessageKey#AUTH_EMAIL_NOT_VERIFIED}。</li>
     * </ul>
     */
    @Transactional
    User findOrCreateUserByEmail(String email, String fullName, String authMethod, boolean emailVerified) {
        if (!emailVerified) {
            // email 未經 provider 驗證 → 拒絕合併或建立，避免帳號接管
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
     * 由 email local part（或固定前綴）產生唯一 username：只保留 [a-z0-9_]，最長 40 字，後綴 4 碼亂數避開撞名。
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
        // 封禁檢查：四條登入路徑（OTP/Google/LINE/Apple、phone）皆 funnel 經此，集中阻擋
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
