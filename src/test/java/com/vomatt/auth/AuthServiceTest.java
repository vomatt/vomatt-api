package com.vomatt.auth;

import com.vomatt.auth.dto.AuthResponse;
import com.vomatt.common.constant.ReservedUsernames;
import com.vomatt.common.email.EmailService;
import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.redis.RedisService;
import com.vomatt.common.security.JwtUtil;
import com.vomatt.common.security.RefreshTokenService;
import com.vomatt.common.sms.SmsService;
import com.vomatt.entity.User;
import com.vomatt.repository.UserRepository;
import com.vomatt.groups.GroupService;
import com.vomatt.votes.VoteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    UserRepository userRepository;
    @Mock
    VoteService voteService;
    @Mock
    GroupService groupService;
    @Mock
    TransactionTemplate transactionTemplate;
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
        ReflectionTestUtils.setField(authService, "appleClientId", "com.vomatt.app");
        ReflectionTestUtils.setField(authService, "googleJwksUrl", "https://www.googleapis.com/oauth2/v3/certs");
        // Resend cooldown slot is free unless a test says otherwise
        lenient().when(redisService.setIfAbsent(anyString(), anyString(), any(), any(Duration.class))).thenReturn(true);
        // Runs the find-or-create callback as if a transaction were open
        lenient().when(transactionTemplate.execute(any()))
                .thenAnswer(inv -> inv.<TransactionCallback<?>>getArgument(0).doInTransaction(null));
    }

    /**
     * Deep-stubs the RestClient fluent chain so that {@code restClient.get().uri(...).retrieve().body(Map.class)}
     * returns the given body (or throws the given exception on retrieve, simulating an Apple JWKS endpoint failure).
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
        lenient().when(uriSpec.uri(anyString())).thenReturn(headersSpec);
        lenient().when(uriSpec.uri(any(java.net.URI.class))).thenReturn(headersSpec);
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
        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
        doNothing().when(emailService).sendPreSignupEmail(anyString(), anyString());

        authService.sendOtp("new@example.com", null, null, false);

        verify(redisService).setOrThrow(eq("otp:email"), eq("new@example.com"), any(OtpRecord.class), eq(Duration.ofSeconds(180)));
        verify(emailService).sendPreSignupEmail(eq("new@example.com"), anyString());
    }

    @Test
    void sendOtp_whenEmailExists_shouldStoreOtpInRedisAndSendLoginMail() {
        when(userRepository.existsByEmail("user@example.com")).thenReturn(true);
        doNothing().when(emailService).sendVerificationEmail(anyString(), anyString());

        authService.sendOtp("user@example.com", null, null, false);

        verify(redisService).setOrThrow(eq("otp:email"), eq("user@example.com"), any(OtpRecord.class), eq(Duration.ofSeconds(180)));
        verify(emailService).sendVerificationEmail(eq("user@example.com"), anyString());
    }

    @Test
    void sendOtp_whenOtpStoreFails_shouldThrowAndNotSendEmail() {
        when(userRepository.existsByEmail("user@example.com")).thenReturn(true);
        org.mockito.Mockito.doThrow(new com.vomatt.common.redis.RedisOperationException("redis down", null))
                .when(redisService).setOrThrow(eq("otp:email"), eq("user@example.com"), any(OtpRecord.class), any(Duration.class));

        assertThatThrownBy(() -> authService.sendOtp("user@example.com", null, null, false))
                .isInstanceOf(com.vomatt.common.redis.RedisOperationException.class);
        // When the OTP write fails, must not send an email with an OTP that cannot be looked up
        verify(emailService, org.mockito.Mockito.never()).sendVerificationEmail(anyString(), anyString());
        verify(emailService, org.mockito.Mockito.never()).sendPreSignupEmail(anyString(), anyString());
        // The cooldown claimed for this send is released, so the user can retry at once
        verify(redisService).delete("otp:email:cooldown", "user@example.com");
    }

    @Test
    void sendOtp_whenWithinCooldown_shouldThrowWithRemainingSeconds() {
        when(redisService.setIfAbsent(eq("otp:email:cooldown"), eq("user@example.com"), any(), any(Duration.class)))
                .thenReturn(false);
        when(redisService.get("otp:email:cooldown", "user@example.com", String.class))
                .thenReturn(Long.toString(Instant.now().minusSeconds(30).getEpochSecond()));

        assertThatThrownBy(() -> authService.sendOtp("user@example.com", null, null, false))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    assertThat(((ApiException) ex).getMessageKey()).isEqualTo(MessageKey.AUTH_OTP_RESEND_COOLDOWN);
                });
        verify(redisService, never()).setOrThrow(eq("otp:email"), anyString(), any(), any(Duration.class));
    }

    @Test
    void shouldRejectSendOtpWhenIdentifierIsLockedOut() {
        when(redisService.hasKeyOrThrow("otp:email:lock", "user@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.sendOtp("user@example.com", null, null, false))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getMessageKey()).isEqualTo(MessageKey.AUTH_OTP_TOO_MANY_ATTEMPTS));
        verify(redisService, never()).setOrThrow(anyString(), anyString(), any(), any(Duration.class));
    }

    @Test
    void shouldRejectPhoneSendOtpWhenIdentifierIsLockedOut() {
        when(redisService.hasKeyOrThrow("otp:phone:lock", "+886912345678")).thenReturn(true);

        assertThatThrownBy(() -> authService.sendOtp(null, "+886912345678", "signup", false))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getMessageKey()).isEqualTo(MessageKey.AUTH_OTP_TOO_MANY_ATTEMPTS));
        verify(smsService, never()).sendOtp(anyString(), anyString());
    }

    @Test
    void shouldDeleteOtpAndCooldownWithoutLeakingCauseWhenEmailSendFails() {
        when(userRepository.existsByEmail("user@example.com")).thenReturn(true);
        org.mockito.Mockito.doThrow(new RuntimeException("smtp.internal:25 refused"))
                .when(emailService).sendVerificationEmail(anyString(), anyString());

        assertThatThrownBy(() -> authService.sendOtp("user@example.com", null, null, false))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getMessageKey()).isEqualTo(MessageKey.AUTH_EMAIL_SEND_FAILED);
                    assertThat(api.getMessageArgs()).isNullOrEmpty();
                });
        verify(redisService).delete("otp:email", "user@example.com");
        verify(redisService).delete("otp:email:cooldown", "user@example.com");
    }

    @Test
    void shouldDeleteOtpAndCooldownWhenSmsSendFails() {
        org.mockito.Mockito.doThrow(new RuntimeException("twilio 500"))
                .when(smsService).sendOtp(anyString(), anyString());

        assertThatThrownBy(() -> authService.sendOtp(null, "+886912345678", "signup", false))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getMessageKey()).isEqualTo(MessageKey.AUTH_SMS_SEND_FAILED));
        verify(redisService).delete("otp:phone", "+886912345678");
        verify(redisService).delete("otp:phone:cooldown", "+886912345678");
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
        // P1-#7: failure count uses atomic INCR (separate counter) to prevent concurrent bypass of the 5-attempt limit
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

        // On reaching the limit, delete both the OTP and the counter and write a lock that outlives them
        verify(redisService).delete("otp:email", "user@example.com");
        verify(redisService).delete("otp:email:fail", "user@example.com");
        verify(redisService).setOrThrow("otp:email:lock", "user@example.com", "1", Duration.ofMinutes(15));
    }

    @Test
    void shouldRejectCorrectCodeWhenAttemptsAlreadyExceeded() {
        // Counter is checked before comparing: even the correct code is rejected once the limit is passed
        OtpRecord otpRecord = new OtpRecord("123456", "login", Instant.now(), 0);
        when(redisService.get(eq("otp:email"), eq("user@example.com"), eq(OtpRecord.class))).thenReturn(otpRecord);
        when(redisService.incrementAndExpire(eq("otp:email:fail"), eq("user@example.com"), any(Duration.class)))
                .thenReturn(6L);

        assertThatThrownBy(() -> authService.verifyOtp("user@example.com", null, "123456", false))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getMessageKey()).isEqualTo(MessageKey.AUTH_OTP_TOO_MANY_ATTEMPTS));
        verify(redisService).setOrThrow("otp:email:lock", "user@example.com", "1", Duration.ofMinutes(15));
        verify(userRepository, never()).findByEmail(anyString());
    }

    @Test
    void shouldRejectVerifyWhenIdentifierIsLockedOut() {
        when(redisService.hasKeyOrThrow("otp:email:lock", "user@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.verifyOtp("user@example.com", null, "123456", false))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getMessageKey()).isEqualTo(MessageKey.AUTH_OTP_TOO_MANY_ATTEMPTS));
        verify(redisService, never()).get(eq("otp:email"), anyString(), eq(OtpRecord.class));
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

    // ── verifyOtp checkRole (P5-#13: check role before consuming OTP / creating account / sending email) ────────

    @Test
    void verifyOtp_checkRoleEmail_nonAdmin_shouldThrow403WithoutConsumingOtpOrCreatingAccount() {
        // Existing non-admin account attempts admin login -> must return 403 before consumeOtp/account creation/email
        User customer = User.builder().id(UUID.randomUUID()).email("user@example.com")
                .displayName("Customer").roles(new String[] { "user" }).build();
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(customer));

        assertThatThrownBy(() -> authService.verifyOtp("user@example.com", null, "123456", true))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getMessageKey())
                        .isEqualTo(MessageKey.AUTH_ADMIN_FORBIDDEN));

        // OTP must not be consumed (neither read nor deleted), no account created, no welcome email sent
        verify(redisService, org.mockito.Mockito.never()).get(anyString(), anyString(), any(Class.class));
        verify(redisService, org.mockito.Mockito.never()).delete(anyString(), anyString());
        verify(userRepository, org.mockito.Mockito.never()).save(any());
        verify(emailService, org.mockito.Mockito.never()).sendWelcomeEmail(anyString(), anyString(), any());
    }

    @Test
    void verifyOtp_checkRoleEmail_noUser_shouldThrow403WithoutConsumingOtp() {
        // No account for email + checkRole=true -> also return 403 before consuming OTP
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
        // Phone path covered too: non-admin -> 403 before consuming OTP
        User customer = User.builder().id(UUID.randomUUID()).phoneNumber("+886912345678")
                .displayName("Customer").roles(new String[] { "user" }).build();
        when(userRepository.findByPhoneNumber("+886912345678")).thenReturn(Optional.of(customer));

        assertThatThrownBy(() -> authService.verifyOtp(null, "0912345678", "123456", true))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getMessageKey())
                        .isEqualTo(MessageKey.AUTH_ADMIN_FORBIDDEN));

        verify(redisService, org.mockito.Mockito.never()).get(anyString(), anyString(), any(Class.class));
        verify(userRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void verifyOtp_checkRoleEmail_admin_shouldConsumeOtpAndReturnToken() {
        // Existing admin account + correct OTP -> completes normally (OTP consumed only after role check passes)
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

    // ── consumeOtp constant-time comparison (P5-#19) ─────────────────────────────────

    @Test
    void verifyOtp_wrongCodeSameLength_shouldRejectViaConstantTimeCompare() {
        // Wrong code of the same length is still rejected (constant-time comparison does not affect correctness)
        OtpRecord otpRecord = new OtpRecord("123456", "login", Instant.now(), 0);
        when(redisService.get(eq("otp:email"), eq("user@example.com"), eq(OtpRecord.class))).thenReturn(otpRecord);
        when(redisService.incrementAndExpire(eq("otp:email:fail"), eq("user@example.com"), any(Duration.class)))
                .thenReturn(1L);

        assertThatThrownBy(() -> authService.verifyOtp("user@example.com", null, "654321", false))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getMessageKey()).isEqualTo(MessageKey.AUTH_INVALID_OTP));

        // A wrong code must not delete the OTP (retries still allowed within the limit)
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
        // AuthService.refresh must not sign the access token itself; it comes from RotateResult
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

    // ── findOrCreateUserByEmail: emailVerified prevents account takeover ────────────

    @Test
    void findOrCreateUserByEmail_existingEmailButUnverified_shouldThrowEmailNotVerified() {
        // Email matches an existing account but is unverified -> must reject before querying the DB, throw AUTH_EMAIL_NOT_VERIFIED
        assertThatThrownBy(() ->
                authService.findOrCreateUserByEmail("victim@example.com", "Attacker", "google", false))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getMessageKey())
                        .isEqualTo(MessageKey.AUTH_EMAIL_NOT_VERIFIED));

        // When unverified, never query the DB, merge, or create an account
        verify(userRepository, org.mockito.Mockito.never()).findByEmail(anyString());
        verify(userRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void findOrCreateUserByEmail_newEmailButUnverified_shouldThrowAndNotCreate() {
        // Email unverified -> must not create an account (also rejected before querying the DB)
        assertThatThrownBy(() ->
                authService.findOrCreateUserByEmail("new@example.com", "Someone", "google", false))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getMessageKey())
                        .isEqualTo(MessageKey.AUTH_EMAIL_NOT_VERIFIED));

        verify(userRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void findOrCreateUserByEmail_existingEmailAndVerified_shouldReturnExistingUser() {
        // Email verified -> keep find-or-create; merging is safe
        User existing = User.builder().id(UUID.randomUUID()).email("user@example.com")
                .displayName("User").roles(new String[] { "user" }).build();
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(existing));

        User result = authService.findOrCreateUserByEmail("user@example.com", "User", "google", true);

        assertThat(result).isSameAs(existing);
        verify(userRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void findOrCreateUserByEmail_newEmailAndVerified_shouldCreateUser() {
        // Email verified and account does not exist -> create a new account
        when(userRepository.findByEmail("fresh@example.com")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User result = authService.findOrCreateUserByEmail("fresh@example.com", "Fresh", "google", true);

        assertThat(result).isNotNull();
        assertThat(result.getEmail()).isEqualTo("fresh@example.com");
        assertThat(result.getDisplayName()).isEqualTo("Fresh");
        assertThat(result.getAuthMethod()).isEqualTo("google");
        // username is generated from the email local part: <local>_<4 digits>
        assertThat(result.getUsername()).matches("fresh_\\d{4}");
        assertThat(result.getRoles()).containsExactly("user");
        assertThat(result.isActive()).isTrue();
        assertThat(result.getAvatarUrl()).matches(com.vomatt.users.BotAvatar.PATTERN);
        verify(userRepository).save(any(User.class));
    }

    @Test
    void findOrCreateUserByEmail_usernameCollision_shouldRetryUntilUnique() {
        when(userRepository.findByEmail("fresh@example.com")).thenReturn(Optional.empty());
        // The first generated username is taken; the second one is available
        when(userRepository.existsByUsername(anyString())).thenReturn(true, false);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User result = authService.findOrCreateUserByEmail("fresh@example.com", null, "email_otp", true);

        verify(userRepository, org.mockito.Mockito.times(2)).existsByUsername(anyString());
        assertThat(result.getUsername()).matches("fresh_\\d{4}");
        // When fullName is empty, displayName falls back to the email local part
        assertThat(result.getDisplayName()).isEqualTo("fresh");
    }

    @Test
    void shouldNeverGenerateReservedUsernameWhenEmailLocalPartIsReserved() {
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User result = authService.findOrCreateUserByEmail("admin@example.com", null, "email_otp", true);

        assertThat(result.getUsername()).matches("admin_\\d{4}");
        assertThat(ReservedUsernames.contains(result.getUsername())).isFalse();
    }

    // ── Email Invitee binding: only a verified email turns email Invitees into user Invitees ────────────

    @Test
    void shouldBindEmailInviteesWhenEmailOtpVerified() {
        OtpRecord otpRecord = new OtpRecord("123456", "signup", Instant.now(), 0);
        when(redisService.get(eq("otp:email"), eq("carol@example.com"), eq(OtpRecord.class))).thenReturn(otpRecord);
        when(redisService.delete(eq("otp:email"), eq("carol@example.com"))).thenReturn(true);
        when(userRepository.findByEmail("carol@example.com")).thenReturn(Optional.empty());
        UUID carolId = UUID.randomUUID();
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User saved = inv.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", carolId);
            return saved;
        });
        when(jwtUtil.generateToken(anyString(), anyString(), any())).thenReturn("mock-token");
        when(refreshTokenService.generate(anyString(), anyString(), any())).thenReturn("mock-refresh-token");

        authService.verifyOtp(" Carol@Example.com", null, "123456", false);

        var order = inOrder(userRepository, voteService, groupService);
        order.verify(userRepository).save(any(User.class));
        order.verify(voteService).bindEmailInvitees(argThat(u -> carolId.equals(u.getId()) && "carol@example.com".equals(u.getEmail())));
        // Polls are locked before Groups
        order.verify(groupService).bindEmailMembers(argThat(u -> carolId.equals(u.getId())));
        verify(transactionTemplate).execute(any());
    }

    @Test
    void shouldResolveSameAccountWhenGmailVariantVerifiesOtp() {
        User existing = User.builder().id(UUID.randomUUID()).email("ab@gmail.com")
                .roles(new String[] { "user" }).build();
        when(redisService.get(eq("otp:email"), eq("ab@gmail.com"), eq(OtpRecord.class)))
                .thenReturn(new OtpRecord("123456", "login", Instant.now(), 0));
        when(redisService.delete(eq("otp:email"), eq("ab@gmail.com"))).thenReturn(true);
        when(userRepository.findByEmail("ab@gmail.com")).thenReturn(Optional.of(existing));
        when(jwtUtil.generateToken(anyString(), anyString(), any())).thenReturn("mock-token");
        when(refreshTokenService.generate(anyString(), anyString(), any())).thenReturn("mock-refresh-token");

        AuthResponse response = authService.verifyOtp("A.B+x@gmail.com", null, "123456", false);

        assertThat(response.user().id()).isEqualTo(existing.getId().toString());
        verify(voteService).bindEmailInvitees(existing);
    }

    @Test
    void shouldBindEmailInviteesWhenGoogleEmailVerified() throws Exception {
        ReflectionTestUtils.setField(authService, "googleClientId", "client-id");
        String idToken = signedGoogleToken(googlePayload("Carol@Example.com", true));
        User carol = User.builder().id(UUID.randomUUID()).email("carol@example.com").displayName("Carol")
                .roles(new String[] { "user" }).build();
        when(userRepository.findByEmail("carol@example.com")).thenReturn(Optional.of(carol));
        when(jwtUtil.generateToken(anyString(), anyString(), any())).thenReturn("mock-token");
        when(refreshTokenService.generate(anyString(), anyString(), any())).thenReturn("mock-refresh-token");

        authService.googleLogin(idToken);

        verify(voteService).bindEmailInvitees(carol);
        verify(groupService).bindEmailMembers(carol);
    }

    @Test
    void shouldNotCopyGooglePictureWhenSigningIn() throws Exception {
        ReflectionTestUtils.setField(authService, "googleClientId", "client-id");
        var claims = googlePayload("carol@example.com", true);
        claims.put("picture", "https://lh3.googleusercontent.com/carol.jpg");
        String idToken = signedGoogleToken(claims);
        User carol = User.builder().id(UUID.randomUUID()).email("carol@example.com").displayName("Carol")
                .roles(new String[] { "user" }).build();
        when(userRepository.findByEmail("carol@example.com")).thenReturn(Optional.of(carol));
        when(jwtUtil.generateToken(anyString(), anyString(), any())).thenReturn("mock-token");
        when(refreshTokenService.generate(anyString(), anyString(), any())).thenReturn("mock-refresh-token");

        authService.googleLogin(idToken);

        assertThat(carol.getAvatarUrl()).isNull();
    }

    @Test
    void shouldNotBindEmailInviteesWhenGoogleEmailUnverified() throws Exception {
        ReflectionTestUtils.setField(authService, "googleClientId", "client-id");
        String idToken = signedGoogleToken(googlePayload("carol@example.com", false));

        assertThatThrownBy(() -> authService.googleLogin(idToken))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getMessageKey()).isEqualTo(MessageKey.AUTH_EMAIL_NOT_VERIFIED));

        verify(voteService, never()).bindEmailInvitees(any());
        verify(groupService, never()).bindEmailMembers(any());
    }

    @Test
    void shouldRejectGoogleTokenWhenAudienceIsAnotherClient() throws Exception {
        ReflectionTestUtils.setField(authService, "googleClientId", "client-id");
        var claims = googlePayload("carol@example.com", true);
        claims.put("aud", "other-client");

        assertGoogleRejected(signedGoogleToken(claims), MessageKey.AUTH_GOOGLE_INVALID_AUDIENCE);
    }

    @Test
    void shouldAcceptGoogleTokenWhenAudienceIsAnyConfiguredClient() throws Exception {
        ReflectionTestUtils.setField(authService, "googleClientId", "web-id, client-id");
        User carol = User.builder().id(UUID.randomUUID()).email("carol@example.com").displayName("Carol")
                .roles(new String[] { "user" }).build();
        when(userRepository.findByEmail("carol@example.com")).thenReturn(Optional.of(carol));
        when(jwtUtil.generateToken(anyString(), anyString(), any())).thenReturn("mock-token");
        when(refreshTokenService.generate(anyString(), anyString(), any())).thenReturn("mock-refresh-token");

        assertThat(authService.googleLogin(signedGoogleToken(googlePayload("carol@example.com", true))).token())
                .isEqualTo("mock-token");
    }

    @Test
    void shouldRejectGoogleTokenWhenIssuerIsNotGoogle() throws Exception {
        ReflectionTestUtils.setField(authService, "googleClientId", "client-id");
        var claims = googlePayload("carol@example.com", true);
        claims.put("iss", "https://evil.example.com");

        assertGoogleRejected(signedGoogleToken(claims), MessageKey.AUTH_GOOGLE_NO_EMAIL);
    }

    @Test
    void shouldRejectGoogleTokenWhenExpired() throws Exception {
        ReflectionTestUtils.setField(authService, "googleClientId", "client-id");
        var claims = googlePayload("carol@example.com", true);
        claims.put("exp", Instant.now().minusSeconds(60).getEpochSecond());

        assertGoogleRejected(signedGoogleToken(claims), MessageKey.AUTH_GOOGLE_NO_EMAIL);
    }

    @Test
    void shouldRejectGoogleTokenWhenSignedByUnknownKey() throws Exception {
        ReflectionTestUtils.setField(authService, "googleClientId", "client-id");
        // Same kid, but the JWKS holds a different public key -> signature check fails
        String idToken = signGoogleToken(googlePayload("carol@example.com", true), rsaKeyPair());
        stubJwksFetch(jwksOf((java.security.interfaces.RSAPublicKey) rsaKeyPair().getPublic()), null);

        assertGoogleRejected(idToken, MessageKey.AUTH_GOOGLE_NO_EMAIL);
    }

    @Test
    void shouldThrowServiceUnavailableWhenGoogleJwksUnreachable() {
        ReflectionTestUtils.setField(authService, "googleClientId", "client-id");
        stubJwksFetch(null, new org.springframework.web.client.ResourceAccessException("timeout"));

        assertThatThrownBy(() -> authService.googleLogin("a.b.c"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getMessageKey()).isEqualTo(MessageKey.COMMON_SERVICE_UNAVAILABLE));
    }

    private void assertGoogleRejected(String idToken, MessageKey expected) {
        assertThatThrownBy(() -> authService.googleLogin(idToken))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    assertThat(((ApiException) ex).getStatus()).isEqualTo(org.springframework.http.HttpStatus.UNAUTHORIZED);
                    assertThat(((ApiException) ex).getMessageKey()).isEqualTo(expected);
                });
        verify(userRepository, never()).findByEmail(anyString());
    }

    private static java.util.Map<String, Object> googlePayload(String email, boolean emailVerified) {
        java.util.Map<String, Object> claims = new java.util.HashMap<>();
        claims.put("iss", "https://accounts.google.com");
        claims.put("aud", "client-id");
        claims.put("exp", Instant.now().plusSeconds(3600).getEpochSecond());
        claims.put("email", email);
        claims.put("email_verified", emailVerified);
        return claims;
    }

    /** Signs the claims with a fresh RSA key and stubs the Google JWKS fetch to serve its public key. */
    private String signedGoogleToken(java.util.Map<String, Object> claims) throws Exception {
        java.security.KeyPair kp = rsaKeyPair();
        stubJwksFetch(jwksOf((java.security.interfaces.RSAPublicKey) kp.getPublic()), null);
        return signGoogleToken(claims, kp);
    }

    private static String signGoogleToken(java.util.Map<String, Object> claims, java.security.KeyPair kp) throws Exception {
        String signingInput = base64Url("{\"alg\":\"RS256\",\"kid\":\"k1\"}") + "."
                + base64Url(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(claims));
        java.security.Signature sig = java.security.Signature.getInstance("SHA256withRSA");
        sig.initSign(kp.getPrivate());
        sig.update(signingInput.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return signingInput + "." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(sig.sign());
    }

    private static java.security.KeyPair rsaKeyPair() throws Exception {
        java.security.KeyPairGenerator gen = java.security.KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        return gen.generateKeyPair();
    }

    private static java.util.Map<String, Object> jwksOf(java.security.interfaces.RSAPublicKey pub) {
        java.util.Base64.Encoder enc = java.util.Base64.getUrlEncoder().withoutPadding();
        return java.util.Map.of("keys", java.util.List.of(java.util.Map.of(
                "kid", "k1",
                "n", enc.encodeToString(pub.getModulus().toByteArray()),
                "e", enc.encodeToString(pub.getPublicExponent().toByteArray()))));
    }

    @Test
    void findOrCreateUserByPhone_newPhone_shouldCreateUserWithGeneratedUsername() {
        when(userRepository.findByPhoneNumber("+886912345678")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User result = authService.findOrCreateUserByPhone("0912345678");

        assertThat(result.getPhoneNumber()).isEqualTo("+886912345678");
        assertThat(result.getEmail()).isNull();
        assertThat(result.getUsername()).matches("user_\\d{4}");
        assertThat(result.getDisplayName()).isEqualTo("+886912345678");
        assertThat(result.getAvatarUrl()).matches(com.vomatt.users.BotAvatar.PATTERN);
    }

    @Test
    void shouldResolveSameAccountWhenOtpSentToLocalFormAndVerifiedAsE164() {
        User existing = User.builder().id(UUID.randomUUID()).phoneNumber("+886912345678")
                .displayName("Phone User").roles(new String[] { "user" }).build();
        when(userRepository.existsByPhoneNumber("+886912345678")).thenReturn(true);
        when(userRepository.findByPhoneNumber("+886912345678")).thenReturn(Optional.of(existing));

        authService.sendOtp(null, "0912345678", "login", false);

        org.mockito.ArgumentCaptor<OtpRecord> sent = org.mockito.ArgumentCaptor.forClass(OtpRecord.class);
        verify(redisService).setOrThrow(eq("otp:phone"), eq("+886912345678"), sent.capture(), any(Duration.class));
        verify(smsService).sendOtp(eq("+886912345678"), eq(sent.getValue().code()));

        when(redisService.get(eq("otp:phone"), eq("+886912345678"), eq(OtpRecord.class))).thenReturn(sent.getValue());
        when(jwtUtil.generateToken(anyString(), any(), any())).thenReturn("access-token");
        when(refreshTokenService.generate(anyString(), any(), any())).thenReturn("refresh-token");

        AuthResponse response = authService.verifyOtp(null, "+886912345678", sent.getValue().code(), false);

        assertThat(response.user().id()).isEqualTo(existing.getId().toString());
        assertThat(response.user().phoneNumber()).isEqualTo("+886912345678");
        assertThat(authService.findOrCreateUserByPhone("0912345678")).isSameAs(existing);
    }

    // ── buildAuthResponse: active=false (suspended) check ───────────────────────────

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
        // Successful login updates lastLoginAt
        assertThat(user.getLastLoginAt()).isNotNull();
        verify(userRepository).save(user);
    }

    // ── appleLogin (P6-#16 external failure classification + #21 JWKS cache) ──────────────────

    @Test
    void appleLogin_whenJwksBodyNull_shouldThrowExternalServiceUnavailableNot401() {
        // JWKS response body is null -> treated as an upstream error -> 503, not a misleading 401 login failure
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
        // Body not null but keys is null -> also an upstream error -> 503
        stubJwksFetch(java.util.Map.of("foo", "bar"), null);

        assertThatThrownBy(() -> authService.appleLogin("a.b.c", null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus())
                        .isEqualTo(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void appleLogin_whenJwksEndpoint5xx_shouldThrowExternalServiceUnavailableNot401() {
        // Apple JWKS endpoint 5xx -> external service failure -> 503, not 401
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
        // Connection failure (ResourceAccessException) -> external service failure -> 503
        var ioError = new org.springframework.web.client.ResourceAccessException("connect timed out");
        stubJwksFetch(null, ioError);

        assertThatThrownBy(() -> authService.appleLogin("a.b.c", null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus())
                        .isEqualTo(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void appleLogin_whenTokenHeaderMissingKid_shouldThrow401InvalidToken() {
        // JWKS OK, but token header has no kid -> cannot locate public key -> 401 invalid_token (credential problem, not 503)
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
        // P6-#21: a second Apple login within the TTL (10 minutes) must not refetch the JWKS endpoint (cache hit)
        java.util.Map<String, Object> jwks = java.util.Map.of(
                "keys", java.util.List.of(java.util.Map.of("kid", "real-kid", "n", "AQAB", "e", "AQAB")));
        stubJwksFetch(jwks, null);

        // Use "header without kid" so both calls end with 401 after fetching JWKS but before signature verification,
        // while JWKS has been fetched and cached; the point is to verify restClient.get() is called only once.
        String header = base64Url("{\"alg\":\"RS256\"}");
        String payload = base64Url("{\"iss\":\"https://appleid.apple.com\"}");
        String idToken = header + "." + payload + ".c2ln";

        assertThatThrownBy(() -> authService.appleLogin(idToken, null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> authService.appleLogin(idToken, null)).isInstanceOf(ApiException.class);

        // JWKS fetched only once (second call hits the cache)
        verify(restClient, org.mockito.Mockito.times(1)).get();
    }

    @Test
    void shouldThrowNotConfiguredWhenAppleClientIdIsBlank() {
        ReflectionTestUtils.setField(authService, "appleClientId", "");

        assertThatThrownBy(() -> authService.appleLogin("a.b.c", null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getMessageKey()).isEqualTo(MessageKey.AUTH_APPLE_NOT_CONFIGURED));
        verify(restClient, never()).get();
    }

    @Test
    void shouldRejectAppleTokenWhenExpIsMissing() throws Exception {
        java.security.KeyPairGenerator gen = java.security.KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        java.security.KeyPair kp = gen.generateKeyPair();
        var pub = (java.security.interfaces.RSAPublicKey) kp.getPublic();
        java.util.Base64.Encoder enc = java.util.Base64.getUrlEncoder().withoutPadding();
        stubJwksFetch(java.util.Map.of("keys", java.util.List.of(java.util.Map.of(
                "kid", "k1",
                "n", enc.encodeToString(pub.getModulus().toByteArray()),
                "e", enc.encodeToString(pub.getPublicExponent().toByteArray())))), null);

        String signingInput = base64Url("{\"alg\":\"RS256\",\"kid\":\"k1\"}") + "."
                + base64Url("{\"iss\":\"https://appleid.apple.com\",\"aud\":\"com.vomatt.app\",\"email\":\"a@b.com\",\"email_verified\":\"true\"}");
        java.security.Signature sig = java.security.Signature.getInstance("SHA256withRSA");
        sig.initSign(kp.getPrivate());
        sig.update(signingInput.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String idToken = signingInput + "." + enc.encodeToString(sig.sign());

        assertThatThrownBy(() -> authService.appleLogin(idToken, null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getMessageKey()).isEqualTo(MessageKey.AUTH_APPLE_TOKEN_EXPIRED));
        verify(userRepository, never()).findByEmail(anyString());
    }

    private static String base64Url(String json) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

}
