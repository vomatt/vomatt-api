package com.vomatt.auth;

import com.vomatt.auth.dto.AuthResponse;
import com.vomatt.common.email.EmailService;
import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.redis.RedisService;
import com.vomatt.common.security.JwtUtil;
import com.vomatt.common.security.RefreshTokenService;
import com.vomatt.common.sms.SmsService;
import com.vomatt.entity.User;
import com.vomatt.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    UserRepository userRepository;
    @Mock
    RedisService redisService;
    @Mock
    EmailService emailService;
    @Mock
    SmsService smsService;
    @Mock
    JwtUtil jwtUtil;
    @Mock
    org.springframework.web.client.RestClient restClient;
    @Mock
    RefreshTokenService refreshTokenService;

    @InjectMocks
    AuthService authService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(authService, "otpTtlSeconds", 180L);
        ReflectionTestUtils.setField(authService, "otpResendCooldownSeconds", 180L);
        ReflectionTestUtils.setField(authService, "appleJwksUrl", "https://appleid.apple.com/auth/keys");
    }

    /**
     * 以 deep-stub 串接 RestClient 的 fluent chain，讓 {@code restClient.get().uri(...).retrieve().body(Map.class)}
     * 回傳指定 body（或在 retrieve 時拋出指定例外，模擬 Apple JWKS endpoint 故障）。
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private void stubJwksFetch(java.util.Map<String, Object> body, RuntimeException retrieveError) {
        org.springframework.web.client.RestClient.RequestHeadersUriSpec uriSpec =
                org.mockito.Mockito.mock(org.springframework.web.client.RestClient.RequestHeadersUriSpec.class);
        org.springframework.web.client.RestClient.RequestHeadersSpec headersSpec =
                org.mockito.Mockito.mock(org.springframework.web.client.RestClient.RequestHeadersSpec.class);
        org.springframework.web.client.RestClient.ResponseSpec responseSpec =
                org.mockito.Mockito.mock(org.springframework.web.client.RestClient.ResponseSpec.class);

        org.mockito.Mockito.when(restClient.get()).thenReturn(uriSpec);
        org.mockito.Mockito.when(uriSpec.uri(anyString())).thenReturn(headersSpec);
        org.mockito.Mockito.when(headersSpec.retrieve()).thenReturn(responseSpec);
        if (retrieveError != null) {
            org.mockito.Mockito.when(responseSpec.body(eq(java.util.Map.class))).thenThrow(retrieveError);
        } else {
            org.mockito.Mockito.when(responseSpec.body(eq(java.util.Map.class))).thenReturn(body);
        }
    }

    // ── sendOtp ──────────────────────────────────────────────────────

    @Test
    void sendOtp_whenEmailNotExist_shouldStoreOtpInRedisAndSendSignupMail() {
        when(redisService.get(eq("otp:email"), eq("new@example.com"), eq(OtpRecord.class))).thenReturn(null);
        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
        doNothing().when(emailService).sendPreSignupEmail(anyString(), anyString());

        authService.sendOtp("new@example.com", null, null, false);

        verify(redisService).setOrThrow(eq("otp:email"), eq("new@example.com"), any(OtpRecord.class), eq(Duration.ofSeconds(180)));
        verify(emailService).sendPreSignupEmail(eq("new@example.com"), anyString());
    }

    @Test
    void sendOtp_whenEmailExists_shouldStoreOtpInRedisAndSendLoginMail() {
        when(redisService.get(eq("otp:email"), eq("user@example.com"), eq(OtpRecord.class))).thenReturn(null);
        when(userRepository.existsByEmail("user@example.com")).thenReturn(true);
        doNothing().when(emailService).sendVerificationEmail(anyString(), anyString());

        authService.sendOtp("user@example.com", null, null, false);

        verify(redisService).setOrThrow(eq("otp:email"), eq("user@example.com"), any(OtpRecord.class), eq(Duration.ofSeconds(180)));
        verify(emailService).sendVerificationEmail(eq("user@example.com"), anyString());
    }

    @Test
    void sendOtp_whenOtpStoreFails_shouldThrowAndNotSendEmail() {
        when(redisService.get(eq("otp:email"), eq("user@example.com"), eq(OtpRecord.class))).thenReturn(null);
        when(userRepository.existsByEmail("user@example.com")).thenReturn(true);
        org.mockito.Mockito.doThrow(new com.vomatt.common.redis.RedisOperationException("redis down", null))
                .when(redisService).setOrThrow(eq("otp:email"), eq("user@example.com"), any(OtpRecord.class), any(Duration.class));

        assertThatThrownBy(() -> authService.sendOtp("user@example.com", null, null, false))
                .isInstanceOf(com.vomatt.common.redis.RedisOperationException.class);
        // OTP 寫入失敗時不得寄出查無的 OTP 信
        verify(emailService, org.mockito.Mockito.never()).sendVerificationEmail(anyString(), anyString());
        verify(emailService, org.mockito.Mockito.never()).sendPreSignupEmail(anyString(), anyString());
    }

    @Test
    void sendOtp_whenWithinCooldown_shouldThrowWithRemainingSeconds() {
        OtpRecord recentOtp = new OtpRecord("123456", "login", Instant.now().minusSeconds(30), 0);
        when(redisService.get(eq("otp:email"), eq("user@example.com"), eq(OtpRecord.class))).thenReturn(recentOtp);

        assertThatThrownBy(() -> authService.sendOtp("user@example.com", null, null, false))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    assertThat(((ApiException) ex).getMessageKey()).isEqualTo(MessageKey.AUTH_OTP_RESEND_COOLDOWN);
                });
    }

    @Test
    void sendOtp_whenCooldownExpired_shouldResend() {
        OtpRecord expiredOtp = new OtpRecord("123456", "login", Instant.now().minusSeconds(200), 0);
        when(redisService.get(eq("otp:email"), eq("user@example.com"), eq(OtpRecord.class))).thenReturn(expiredOtp);
        when(userRepository.existsByEmail("user@example.com")).thenReturn(true);
        doNothing().when(emailService).sendVerificationEmail(anyString(), anyString());

        authService.sendOtp("user@example.com", null, null, false);

        verify(redisService).setOrThrow(eq("otp:email"), eq("user@example.com"), any(OtpRecord.class), eq(Duration.ofSeconds(180)));
    }

    // ── verifyOtp ────────────────────────────────────────────────────

    @Test
    void verifyOtp_withInvalidCode_shouldThrow() {
        when(redisService.get(eq("otp:email"), eq("user@example.com"), eq(OtpRecord.class))).thenReturn(null);

        assertThatThrownBy(() -> authService.verifyOtp("user@example.com", null, "000000", false))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    assertThat(((ApiException) ex).getMessageKey()).isEqualTo(MessageKey.AUTH_OTP_INVALID_OR_EXPIRED);
                });
    }

    @Test
    void verifyOtp_withWrongCode_shouldIncrementAtomicFailCounter() {
        // P1-#7：失敗計數改用原子 INCR（獨立 counter），防並發繞過 5 次上限
        OtpRecord otpRecord = new OtpRecord("123456", "login", Instant.now(), 0);
        when(redisService.get(eq("otp:email"), eq("user@example.com"), eq(OtpRecord.class))).thenReturn(otpRecord);
        when(redisService.incrementAndExpire(eq("otp:email:fail"), eq("user@example.com"), any(Duration.class)))
                .thenReturn(1L);

        assertThatThrownBy(() -> authService.verifyOtp("user@example.com", null, "999999", false))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getMessageKey()).isEqualTo(MessageKey.AUTH_INVALID_OTP));

        verify(redisService).incrementAndExpire(eq("otp:email:fail"), eq("user@example.com"), any(Duration.class));
    }

    @Test
    void verifyOtp_whenFailCounterReachesMax_shouldThrowLockedAndInvalidateOtp() {
        OtpRecord otpRecord = new OtpRecord("123456", "login", Instant.now(), 0);
        when(redisService.get(eq("otp:email"), eq("user@example.com"), eq(OtpRecord.class))).thenReturn(otpRecord);
        when(redisService.incrementAndExpire(eq("otp:email:fail"), eq("user@example.com"), any(Duration.class)))
                .thenReturn(5L);

        assertThatThrownBy(() -> authService.verifyOtp("user@example.com", null, "999999", false))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getMessageKey()).isEqualTo(MessageKey.AUTH_OTP_TOO_MANY_ATTEMPTS));

        // 達上限後一併刪除 OTP 與計數，使後續嘗試一律失效
        verify(redisService).delete("otp:email", "user@example.com");
        verify(redisService).delete("otp:email:fail", "user@example.com");
    }

    @Test
    void verifyOtp_withValidCode_shouldReturnTokenAndDeleteOtp() {
        OtpRecord otpRecord = new OtpRecord("123456", "login", Instant.now(), 0);
        User user = User.builder().id(UUID.randomUUID()).email("user@example.com").displayName("Test User")
                .roles(new String[] { "user" }).build();

        when(redisService.get(eq("otp:email"), eq("user@example.com"), eq(OtpRecord.class))).thenReturn(otpRecord);
        when(redisService.delete(eq("otp:email"), eq("user@example.com"))).thenReturn(true);
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(jwtUtil.generateToken(anyString(), anyString(), any())).thenReturn("mock-token");
        when(refreshTokenService.generate(anyString(), anyString(), any())).thenReturn("mock-refresh-token");

        AuthResponse response = authService.verifyOtp("user@example.com", null, "123456", false);

        assertThat(response.token()).isEqualTo("mock-token");
        assertThat(response.user().email()).isEqualTo("user@example.com");
        verify(redisService).delete("otp:email", "user@example.com");
    }

    // ── verifyOtp checkRole（P5-#13：消耗 OTP / 建帳號 / 寄信前先驗角色）────────

    @Test
    void verifyOtp_checkRoleEmail_nonAdmin_shouldThrow403WithoutConsumingOtpOrCreatingAccount() {
        // 非 admin 既有帳號嘗試 admin 登入 → 必須在 consumeOtp/建帳號/寄信之前回 403
        User customer = User.builder().id(UUID.randomUUID()).email("user@example.com")
                .displayName("Customer").roles(new String[] { "user" }).build();
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(customer));

        assertThatThrownBy(() -> authService.verifyOtp("user@example.com", null, "123456", true))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getMessageKey())
                        .isEqualTo(MessageKey.AUTH_ADMIN_FORBIDDEN));

        // OTP 不可被消耗（不讀也不刪）、帳號不可建立、歡迎信不可寄出
        verify(redisService, org.mockito.Mockito.never()).get(anyString(), anyString(), any(Class.class));
        verify(redisService, org.mockito.Mockito.never()).delete(anyString(), anyString());
        verify(userRepository, org.mockito.Mockito.never()).save(any());
        verify(emailService, org.mockito.Mockito.never()).sendWelcomeEmail(anyString(), anyString());
    }

    @Test
    void verifyOtp_checkRoleEmail_noUser_shouldThrow403WithoutConsumingOtp() {
        // email 查無帳號 + checkRole=true → 同樣在消耗 OTP 前回 403
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.verifyOtp("ghost@example.com", null, "123456", true))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getMessageKey())
                        .isEqualTo(MessageKey.AUTH_ADMIN_FORBIDDEN));

        verify(redisService, org.mockito.Mockito.never()).get(anyString(), anyString(), any(Class.class));
        verify(userRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void verifyOtp_checkRolePhone_nonAdmin_shouldThrow403WithoutConsumingOtp() {
        // phone 路徑同樣涵蓋：非 admin → 消耗 OTP 前回 403
        User customer = User.builder().id(UUID.randomUUID()).phoneNumber("0912345678")
                .displayName("Customer").roles(new String[] { "user" }).build();
        when(userRepository.findByPhoneNumber("0912345678")).thenReturn(Optional.of(customer));

        assertThatThrownBy(() -> authService.verifyOtp(null, "0912345678", "123456", true))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getMessageKey())
                        .isEqualTo(MessageKey.AUTH_ADMIN_FORBIDDEN));

        verify(redisService, org.mockito.Mockito.never()).get(anyString(), anyString(), any(Class.class));
        verify(userRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void verifyOtp_checkRoleEmail_admin_shouldConsumeOtpAndReturnToken() {
        // admin 既有帳號 + 正確 OTP → 正常完成（角色檢查通過後才消耗 OTP）
        OtpRecord otpRecord = new OtpRecord("123456", "login", Instant.now(), 0);
        User admin = User.builder().id(UUID.randomUUID()).email("admin@example.com")
                .displayName("Admin").roles(new String[] { "admin" }).build();
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
        when(redisService.get(eq("otp:email"), eq("admin@example.com"), eq(OtpRecord.class))).thenReturn(otpRecord);
        when(redisService.delete(eq("otp:email"), eq("admin@example.com"))).thenReturn(true);
        when(jwtUtil.generateToken(anyString(), anyString(), any())).thenReturn("admin-token");
        when(refreshTokenService.generate(anyString(), anyString(), any())).thenReturn("admin-refresh");

        AuthResponse response = authService.verifyOtp("admin@example.com", null, "123456", true);

        assertThat(response.token()).isEqualTo("admin-token");
        verify(redisService).delete("otp:email", "admin@example.com");
    }

    // ── consumeOtp 常數時間比對（P5-#19）─────────────────────────────────

    @Test
    void verifyOtp_wrongCodeSameLength_shouldRejectViaConstantTimeCompare() {
        // 同長度錯誤碼仍被拒絕（常數時間比對不影響正確性）
        OtpRecord otpRecord = new OtpRecord("123456", "login", Instant.now(), 0);
        when(redisService.get(eq("otp:email"), eq("user@example.com"), eq(OtpRecord.class))).thenReturn(otpRecord);
        when(redisService.incrementAndExpire(eq("otp:email:fail"), eq("user@example.com"), any(Duration.class)))
                .thenReturn(1L);

        assertThatThrownBy(() -> authService.verifyOtp("user@example.com", null, "654321", false))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getMessageKey()).isEqualTo(MessageKey.AUTH_INVALID_OTP));

        // 錯誤碼不得刪除 OTP（仍可於上限內重試）
        verify(redisService, org.mockito.Mockito.never()).delete("otp:email", "user@example.com");
    }

    // ── refresh ──────────────────────────────────────────────────────

    @Test
    void refresh_returnsAccessAndRefreshTokenFromRotateResult() {
        RefreshTokenService.RefreshTokenData data = new RefreshTokenService.RefreshTokenData(
                "user-x", "x@example.com", List.of("user"));
        RefreshTokenService.RotateResult rotateResult = new RefreshTokenService.RotateResult(
                "new-access", "new-refresh", data);
        when(refreshTokenService.rotate("old-refresh")).thenReturn(rotateResult);

        AuthResponse response = authService.refresh("old-refresh");

        assertThat(response.token()).isEqualTo("new-access");
        assertThat(response.refreshToken()).isEqualTo("new-refresh");
        // AuthService.refresh 不應自行 sign access token，access token 來自 RotateResult
        verify(jwtUtil, org.mockito.Mockito.never()).generateToken(anyString(), anyString(), any());
    }

    @Test
    void refresh_nullResult_throwsRefreshTokenInvalid() {
        when(refreshTokenService.rotate("bad-token")).thenReturn(null);

        assertThatThrownBy(() -> authService.refresh("bad-token"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getMessageKey())
                        .isEqualTo(MessageKey.AUTH_REFRESH_TOKEN_INVALID));
    }

    @Test
    void refresh_reuseException_mapsToSuspiciousLogin() {
        when(refreshTokenService.rotate("reused-token"))
                .thenThrow(new com.vomatt.common.security.RefreshTokenReuseException("reuse"));

        assertThatThrownBy(() -> authService.refresh("reused-token"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getMessageKey())
                        .isEqualTo(MessageKey.AUTH_SUSPICIOUS_LOGIN));
    }

    // ── findOrCreateUserByEmail：emailVerified 防帳號接管 ────────────

    @Test
    void findOrCreateUserByEmail_existingEmailButUnverified_shouldThrowEmailNotVerified() {
        // email 撞到既有帳號但未驗證 → 必須在查 DB 之前就拒絕，拋 AUTH_EMAIL_NOT_VERIFIED
        assertThatThrownBy(() ->
                authService.findOrCreateUserByEmail("victim@example.com", "Attacker", "google", false))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getMessageKey())
                        .isEqualTo(MessageKey.AUTH_EMAIL_NOT_VERIFIED));

        // 未驗證情境下絕不可查 DB、合併或建立帳號
        verify(userRepository, org.mockito.Mockito.never()).findByEmail(anyString());
        verify(userRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void findOrCreateUserByEmail_newEmailButUnverified_shouldThrowAndNotCreate() {
        // email 未驗證 → 不得建立帳號（同樣在查 DB 前拒絕）
        assertThatThrownBy(() ->
                authService.findOrCreateUserByEmail("new@example.com", "Someone", "google", false))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getMessageKey())
                        .isEqualTo(MessageKey.AUTH_EMAIL_NOT_VERIFIED));

        verify(userRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void findOrCreateUserByEmail_existingEmailAndVerified_shouldReturnExistingUser() {
        // email 已驗證 → 維持 find-or-create，合併安全
        User existing = User.builder().id(UUID.randomUUID()).email("user@example.com")
                .displayName("User").roles(new String[] { "user" }).build();
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(existing));

        User result = authService.findOrCreateUserByEmail("user@example.com", "User", "google", true);

        assertThat(result).isSameAs(existing);
        verify(userRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void findOrCreateUserByEmail_newEmailAndVerified_shouldCreateUser() {
        // email 已驗證且帳號不存在 → 建立新帳號
        when(userRepository.findByEmail("fresh@example.com")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User result = authService.findOrCreateUserByEmail("fresh@example.com", "Fresh", "google", true);

        assertThat(result).isNotNull();
        assertThat(result.getEmail()).isEqualTo("fresh@example.com");
        assertThat(result.getDisplayName()).isEqualTo("Fresh");
        assertThat(result.getAuthMethod()).isEqualTo("google");
        // username 由 email local part 自動產生：<local>_<4 碼>
        assertThat(result.getUsername()).matches("fresh_\\d{4}");
        assertThat(result.getRoles()).containsExactly("user");
        assertThat(result.isActive()).isTrue();
        verify(userRepository).save(any(User.class));
    }

    @Test
    void findOrCreateUserByEmail_usernameCollision_shouldRetryUntilUnique() {
        when(userRepository.findByEmail("fresh@example.com")).thenReturn(Optional.empty());
        // 第一次產生的 username 已被佔用，第二次才可用
        when(userRepository.existsByUsername(anyString())).thenReturn(true, false);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User result = authService.findOrCreateUserByEmail("fresh@example.com", null, "email_otp", true);

        verify(userRepository, org.mockito.Mockito.times(2)).existsByUsername(anyString());
        assertThat(result.getUsername()).matches("fresh_\\d{4}");
        // fullName 為空時 displayName 退回 email local part
        assertThat(result.getDisplayName()).isEqualTo("fresh");
    }

    @Test
    void findOrCreateUserByPhone_newPhone_shouldCreateUserWithGeneratedUsername() {
        when(userRepository.findByPhoneNumber("0912345678")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User result = authService.findOrCreateUserByPhone("0912345678");

        assertThat(result.getPhoneNumber()).isEqualTo("0912345678");
        assertThat(result.getEmail()).isNull();
        assertThat(result.getUsername()).matches("user_\\d{4}");
        assertThat(result.getDisplayName()).isEqualTo("0912345678");
    }

    // ── buildAuthResponse：active=false（停權）檢查 ───────────────────────────

    @Test
    void buildAuthResponse_inactiveUser_shouldThrowForbidden() {
        User banned = User.builder().id(UUID.randomUUID()).email("banned@example.com")
                .displayName("Banned").roles(new String[] { "user" }).active(false).build();

        assertThatThrownBy(() -> authService.buildAuthResponse(banned))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getMessageKey())
                        .isEqualTo(MessageKey.AUTH_ACCOUNT_BANNED));

        verify(jwtUtil, org.mockito.Mockito.never()).generateToken(anyString(), anyString(), any());
        verify(userRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void buildAuthResponse_activeUser_shouldReturnAuthResponse() {
        User user = User.builder().id(UUID.randomUUID()).email("ok@example.com").username("ok_user")
                .displayName("OK User").roles(new String[] { "user" }).active(true)
                .build();
        when(jwtUtil.generateToken(anyString(), anyString(), any())).thenReturn("ok-token");
        when(refreshTokenService.generate(anyString(), anyString(), any())).thenReturn("ok-refresh");

        AuthResponse response = authService.buildAuthResponse(user);

        assertThat(response.token()).isEqualTo("ok-token");
        assertThat(response.refreshToken()).isEqualTo("ok-refresh");
        assertThat(response.user().email()).isEqualTo("ok@example.com");
        assertThat(response.user().username()).isEqualTo("ok_user");
        assertThat(response.user().roles()).containsExactly("user");
        // 登入成功會更新 lastLoginAt
        assertThat(user.getLastLoginAt()).isNotNull();
        verify(userRepository).save(user);
    }

    // ── appleLogin（P6-#16 外部故障分類 + #21 JWKS 快取）──────────────────

    @Test
    void appleLogin_whenJwksBodyNull_shouldThrowExternalServiceUnavailableNot401() {
        // JWKS 回應 body 為 null → 視為上游回應異常 → 503，而非誤報 401 登入失敗
        stubJwksFetch(null, null);

        assertThatThrownBy(() -> authService.appleLogin("a.b.c", null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    assertThat(((ApiException) ex).getStatus())
                            .isEqualTo(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(((ApiException) ex).getMessageKey())
                            .isEqualTo(MessageKey.COMMON_SERVICE_UNAVAILABLE);
                });
    }

    @Test
    void appleLogin_whenJwksKeysNull_shouldThrowExternalServiceUnavailableNot401() {
        // body 非 null 但 keys 為 null → 同視為上游異常 → 503
        stubJwksFetch(java.util.Map.of("foo", "bar"), null);

        assertThatThrownBy(() -> authService.appleLogin("a.b.c", null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus())
                        .isEqualTo(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void appleLogin_whenJwksEndpoint5xx_shouldThrowExternalServiceUnavailableNot401() {
        // Apple JWKS endpoint 5xx → 外部服務故障 → 503，非 401
        var serverError = new org.springframework.web.client.HttpServerErrorException(
                org.springframework.http.HttpStatus.BAD_GATEWAY);
        stubJwksFetch(null, serverError);

        assertThatThrownBy(() -> authService.appleLogin("a.b.c", null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    assertThat(((ApiException) ex).getStatus())
                            .isEqualTo(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(((ApiException) ex).getMessageKey())
                            .isEqualTo(MessageKey.COMMON_SERVICE_UNAVAILABLE);
                });
    }

    @Test
    void appleLogin_whenJwksConnectivityFailure_shouldThrowExternalServiceUnavailableNot401() {
        // 連線失敗（ResourceAccessException）→ 外部服務故障 → 503
        var ioError = new org.springframework.web.client.ResourceAccessException("connect timed out");
        stubJwksFetch(null, ioError);

        assertThatThrownBy(() -> authService.appleLogin("a.b.c", null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus())
                        .isEqualTo(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void appleLogin_whenTokenHeaderMissingKid_shouldThrow401InvalidToken() {
        // JWKS 正常，但 token header 無 kid → 無法定位公鑰 → 401 invalid_token（憑證問題，非 503）
        java.util.Map<String, Object> jwks = java.util.Map.of(
                "keys", java.util.List.of(java.util.Map.of("kid", "real-kid", "n", "AQAB", "e", "AQAB")));
        stubJwksFetch(jwks, null);

        String header = base64Url("{\"alg\":\"RS256\"}");
        String payload = base64Url("{\"iss\":\"https://appleid.apple.com\"}");
        String idToken = header + "." + payload + ".c2ln";

        assertThatThrownBy(() -> authService.appleLogin(idToken, null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    assertThat(((ApiException) ex).getStatus())
                            .isEqualTo(org.springframework.http.HttpStatus.UNAUTHORIZED);
                    assertThat(((ApiException) ex).getMessageKey())
                            .isEqualTo(MessageKey.AUTH_APPLE_INVALID_TOKEN);
                });
    }

    @Test
    void appleLogin_secondCallWithinTtl_shouldNotRefetchJwks() {
        // P6-#21：TTL（10 分鐘）內第二次 Apple 登入不應重打 JWKS endpoint（快取命中）
        java.util.Map<String, Object> jwks = java.util.Map.of(
                "keys", java.util.List.of(java.util.Map.of("kid", "real-kid", "n", "AQAB", "e", "AQAB")));
        stubJwksFetch(jwks, null);

        // 用「header 無 kid」讓兩次呼叫都在 JWKS 取得之後、簽章驗證之前以 401 收斂，
        // 但 JWKS 已成功抓取並快取；重點在於驗證 restClient.get() 只被呼叫一次。
        String header = base64Url("{\"alg\":\"RS256\"}");
        String payload = base64Url("{\"iss\":\"https://appleid.apple.com\"}");
        String idToken = header + "." + payload + ".c2ln";

        assertThatThrownBy(() -> authService.appleLogin(idToken, null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> authService.appleLogin(idToken, null)).isInstanceOf(ApiException.class);

        // JWKS 僅抓取一次（第二次走快取）
        verify(restClient, org.mockito.Mockito.times(1)).get();
    }

    private static String base64Url(String json) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

}
