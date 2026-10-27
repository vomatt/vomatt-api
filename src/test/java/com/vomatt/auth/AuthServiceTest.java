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
import com.vomatt.invitelists.InviteListService;
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
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
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
    InviteListService inviteListService;
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
    @Mock
    com.vomatt.common.security.AppKey appKey;

    @InjectMocks
    AuthService authService;

    private static final String IP = "203.0.113.7";
    private static final byte[] OTP_KEY = "auth-service-test-otp-key".getBytes();

    /** Redis key part of an identifier (OTP keys never carry the identifier itself). */
    private static String k(String identifier) {
        return AuthService.redisKey(identifier);
    }

    /** What Redis holds for this identifier's code. */
    private static String h(String identifier, String code) {
        return com.vomatt.common.security.AppKey.hmacHex(OTP_KEY, identifier + ":" + code);
    }

    /** Redis key of the failure counter / lock / level for this identifier guessing from {@link #IP}. */
    private static String attempt(String identifier) {
        return AuthService.attemptKey(identifier, IP);
    }

    /** The OTP written by sendOtp, which stores it in one transaction with the failure counter's TTL extension. */
    private java.util.List<RedisService.RedisWrite> storedOtpWrites() {
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<java.util.List<RedisService.RedisWrite>> writes =
                org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        verify(redisService).execInTransaction(writes.capture());
        return writes.getValue();
    }

    private OtpRecord storedOtp(String namespace, String key) {
        RedisService.RedisWrite set = storedOtpWrites().get(0);
        assertThat(set.op()).isEqualTo(RedisService.RedisWrite.Op.SET);
        assertThat(set.cacheName()).isEqualTo(namespace);
        assertThat(set.key()).isEqualTo(key);
        assertThat(set.ttl()).isEqualTo(Duration.ofSeconds(180));
        return (OtpRecord) set.value();
    }

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(authService, "otpTtlSeconds", 180L);
        lenient().when(appKey.derive("otp")).thenReturn(OTP_KEY);
        ReflectionTestUtils.setField(authService, "otpResendCooldownSeconds", 180L);
        ReflectionTestUtils.setField(authService, "otpDailyLimitEmail", 20L);
        ReflectionTestUtils.setField(authService, "otpDailyLimitPhone", 5L);
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

        authService.sendOtp("new@example.com", null, null, IP);

        storedOtp("otp:email", k("new@example.com"));
        verify(emailService).sendPreSignupEmail(eq("new@example.com"), anyString());
    }

    @Test
    void sendOtp_whenEmailExists_shouldStoreOtpInRedisAndSendLoginMail() {
        when(userRepository.existsByEmail("user@example.com")).thenReturn(true);
        doNothing().when(emailService).sendVerificationEmail(anyString(), anyString());

        authService.sendOtp("user@example.com", null, null, IP);

        storedOtp("otp:email", k("user@example.com"));
        verify(emailService).sendVerificationEmail(eq("user@example.com"), anyString());
    }

    @Test
    void sendOtp_whenOtpStoreFails_shouldThrowAndNotSendEmail() {
        when(userRepository.existsByEmail("user@example.com")).thenReturn(true);
        org.mockito.Mockito.doThrow(new com.vomatt.common.redis.RedisOperationException("redis down", null))
                .when(redisService).execInTransaction(any());

        assertThatThrownBy(() -> authService.sendOtp("user@example.com", null, null, IP))
                .isInstanceOf(com.vomatt.common.redis.RedisOperationException.class);
        // When the OTP write fails, must not send an email with an OTP that cannot be looked up
        verify(emailService, org.mockito.Mockito.never()).sendVerificationEmail(anyString(), anyString());
        verify(emailService, org.mockito.Mockito.never()).sendPreSignupEmail(anyString(), anyString());
        // The cooldown claimed for this send is released, so the user can retry at once
        verify(redisService).delete("otp:email:cooldown", k("user@example.com"));
    }

    @Test
    void sendOtp_whenWithinCooldown_shouldThrowWithRemainingSeconds() {
        when(redisService.setIfAbsent(eq("otp:email:cooldown"), eq(k("user@example.com")), any(), any(Duration.class)))
                .thenReturn(false);
        when(redisService.get("otp:email:cooldown", k("user@example.com"), String.class))
                .thenReturn(Long.toString(Instant.now().plusSeconds(30).getEpochSecond()));

        assertThatThrownBy(() -> authService.sendOtp("user@example.com", null, null, IP))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    assertThat(((ApiException) ex).getMessageKey()).isEqualTo(MessageKey.AUTH_OTP_RESEND_COOLDOWN);
                    assertThat((long) ((ApiException) ex).getMessageArgs()[0]).isBetween(29L, 30L);
                });
        verify(redisService, never()).execInTransaction(any());
    }

    @Test
    void shouldRejectSendOtpWhenIdentifierIsLockedOut() {
        when(redisService.hasKeyOrThrow("otp:email:lock", attempt("user@example.com"))).thenReturn(true);

        assertThatThrownBy(() -> authService.sendOtp("user@example.com", null, null, IP))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getMessageKey()).isEqualTo(MessageKey.AUTH_OTP_TOO_MANY_ATTEMPTS));
        verify(redisService, never()).execInTransaction(any());
    }

    @Test
    void shouldRejectPhoneSendOtpWhenIdentifierIsLockedOut() {
        when(redisService.hasKeyOrThrow("otp:phone:lock", attempt("+886912345678"))).thenReturn(true);

        assertThatThrownBy(() -> authService.sendOtp(null, "+886912345678", "signup", IP))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getMessageKey()).isEqualTo(MessageKey.AUTH_OTP_TOO_MANY_ATTEMPTS));
        verify(smsService, never()).sendOtp(anyString(), anyString());
    }

    @Test
    void shouldKeepShortCooldownWithoutLeakingCauseWhenEmailSendFails() {
        when(userRepository.existsByEmail("user@example.com")).thenReturn(true);
        org.mockito.Mockito.doThrow(new RuntimeException("smtp.internal:25 refused"))
                .when(emailService).sendVerificationEmail(anyString(), anyString());

        assertThatThrownBy(() -> authService.sendOtp("user@example.com", null, null, IP))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getMessageKey()).isEqualTo(MessageKey.AUTH_EMAIL_SEND_FAILED);
                    assertThat(api.getMessageArgs()).isNullOrEmpty();
                });
        verify(redisService).delete("otp:email", k("user@example.com"));
        verify(redisService).set(eq("otp:email:cooldown"), eq(k("user@example.com")), anyString(), eq(Duration.ofSeconds(30)));
        verify(redisService, never()).delete("otp:email:cooldown", k("user@example.com"));
        // The failed send gives its daily-quota slot back
        verify(redisService).incrementAndExpire(eq("otp:email:daily"), startsWith(k("user@example.com") + ":"), eq(-1L), eq(Duration.ofDays(1)));
    }

    @Test
    void shouldKeepShortCooldownWhenSmsSendFails() {
        org.mockito.Mockito.doThrow(new RuntimeException("twilio 500"))
                .when(smsService).sendOtp(anyString(), anyString());

        assertThatThrownBy(() -> authService.sendOtp(null, "+886912345678", "signup", IP))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getMessageKey()).isEqualTo(MessageKey.AUTH_SMS_SEND_FAILED));
        verify(redisService).delete("otp:phone", k("+886912345678"));
        verify(redisService).set(eq("otp:phone:cooldown"), eq(k("+886912345678")), anyString(), eq(Duration.ofSeconds(30)));
        verify(redisService, never()).delete("otp:phone:cooldown", k("+886912345678"));
    }

    // ── verifyOtp ────────────────────────────────────────────────────

    @Test
    void verifyOtp_withInvalidCode_shouldThrow() {
        when(redisService.get(eq("otp:email"), eq(k("user@example.com")), eq(OtpRecord.class))).thenReturn(null);

        assertThatThrownBy(() -> authService.verifyOtp("user@example.com", null, "000000", IP))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    assertThat(((ApiException) ex).getMessageKey()).isEqualTo(MessageKey.AUTH_OTP_INVALID_OR_EXPIRED);
                });
    }

    @Test
    void shouldStillRefundQuotaAndReportSendFailureWhenRedisCleanupFails() {
        when(userRepository.existsByEmail("user@example.com")).thenReturn(true);
        org.mockito.Mockito.doThrow(new RuntimeException("smtp down")).when(emailService).sendVerificationEmail(anyString(), anyString());
        when(redisService.delete("otp:email", k("user@example.com"))).thenThrow(new com.vomatt.common.redis.RedisOperationException("redis down", null));

        assertThatThrownBy(() -> authService.sendOtp("user@example.com", null, null, IP))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getMessageKey()).isEqualTo(MessageKey.AUTH_EMAIL_SEND_FAILED));
        verify(redisService).incrementAndExpire(eq("otp:email:daily"), startsWith(k("user@example.com") + ":"), eq(-1L), eq(Duration.ofDays(1)));
    }

    @Test
    void shouldRejectSendOtpWhenDailyLimitReached() {
        when(userRepository.existsByEmail("user@example.com")).thenReturn(true);
        when(redisService.incrementAndExpire(eq("otp:email:daily"), startsWith(k("user@example.com") + ":"), eq(Duration.ofDays(1))))
                .thenReturn(21L);

        assertThatThrownBy(() -> authService.sendOtp("user@example.com", null, null, IP))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getMessageKey()).isEqualTo(MessageKey.AUTH_OTP_DAILY_LIMIT));

        verify(redisService, never()).execInTransaction(any());
        verify(emailService, never()).sendVerificationEmail(anyString(), anyString());
    }

    @Test
    void shouldSendPhoneOtpWhenDailyLimitNotReached() {
        when(userRepository.existsByPhoneNumber("+886912345678")).thenReturn(true);
        when(redisService.incrementAndExpire(eq("otp:phone:daily"), startsWith(k("+886912345678") + ":"), eq(Duration.ofDays(1))))
                .thenReturn(5L);

        authService.sendOtp(null, "0912345678", "login", IP);

        verify(smsService).sendOtp(eq("+886912345678"), anyString());
        when(redisService.incrementAndExpire(eq("otp:phone:daily"), startsWith(k("+886912345678") + ":"), eq(Duration.ofDays(1))))
                .thenReturn(6L);
        assertThatThrownBy(() -> authService.sendOtp(null, "0912345678", "login", IP))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getMessageKey()).isEqualTo(MessageKey.AUTH_OTP_DAILY_LIMIT));
    }

    @Test
    void verifyOtp_withWrongCode_shouldIncrementAtomicFailCounter() {
        // P1-#7: failure count uses atomic INCR (separate counter) to prevent concurrent bypass of the 3-attempt limit
        OtpRecord otpRecord = new OtpRecord(h("user@example.com", "123456"), "login", Instant.now(), 0);
        when(redisService.get(eq("otp:email"), eq(k("user@example.com")), eq(OtpRecord.class))).thenReturn(otpRecord);
        when(redisService.incrementAndExpire(eq("otp:email:fail"), eq(attempt("user@example.com")), any(Duration.class)))
                .thenReturn(1L);

        assertThatThrownBy(() -> authService.verifyOtp("user@example.com", null, "999999", IP))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getMessageKey()).isEqualTo(MessageKey.AUTH_INVALID_OTP));

        verify(redisService).incrementAndExpire(eq("otp:email:fail"), eq(attempt("user@example.com")), any(Duration.class));
    }

    private void stubWrongCodeAttempt(long attempts) {
        OtpRecord otpRecord = new OtpRecord(h("user@example.com", "123456"), "login", Instant.now(), 0);
        when(redisService.get(eq("otp:email"), eq(k("user@example.com")), eq(OtpRecord.class))).thenReturn(otpRecord);
        when(redisService.incrementAndExpire(eq("otp:email:fail"), eq(attempt("user@example.com")), any(Duration.class)))
                .thenReturn(attempts);
        // First guess on this OTP across all IPs, unless a test says otherwise
        lenient().when(redisService.incrementAndExpire(eq("otp:email:guesses"), startsWith(k("user@example.com") + ":"), any(Duration.class)))
                .thenReturn(1L);
    }

    private void assertTooManyAttempts(String code) {
        assertThatThrownBy(() -> authService.verifyOtp("user@example.com", null, code, IP))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getMessageKey()).isEqualTo(MessageKey.AUTH_OTP_TOO_MANY_ATTEMPTS));
    }

    @Test
    void shouldLockIdentifierAndIpForFiveMinutesWhenThirdCodeIsWrong() {
        stubWrongCodeAttempt(3L);
        when(redisService.incrementAndExpire(eq("otp:email:level"), eq(attempt("user@example.com")), any(Duration.class)))
                .thenReturn(1L);

        assertTooManyAttempts("999999");

        verify(redisService).setOrThrow("otp:email:lock", attempt("user@example.com"), "1", Duration.ofMinutes(5));
        // The level outlives the lock by 24h so the next lockout doubles
        verify(redisService).expire("otp:email:level", attempt("user@example.com"), Duration.ofMinutes(5).plusHours(24));
        // The counter is kept so in-flight guesses cannot escalate again; the OTP stays valid for the real user on other IPs
        verify(redisService, never()).delete(eq("otp:email:fail"), anyString());
        verify(redisService, never()).delete("otp:email", k("user@example.com"));
    }

    @Test
    void shouldDoubleLockDurationWhenLockedOutAgain() {
        stubWrongCodeAttempt(3L);
        when(redisService.incrementAndExpire(eq("otp:email:level"), eq(attempt("user@example.com")), any(Duration.class)))
                .thenReturn(2L, 3L);

        assertTooManyAttempts("999999");
        assertTooManyAttempts("999999");

        verify(redisService).setOrThrow("otp:email:lock", attempt("user@example.com"), "1", Duration.ofMinutes(10));
        verify(redisService).setOrThrow("otp:email:lock", attempt("user@example.com"), "1", Duration.ofMinutes(20));
    }

    @Test
    void shouldCapLockDurationAt24HoursWhenLockoutLevelIsHigh() {
        assertThat(AuthService.lockDuration(1)).isEqualTo(Duration.ofMinutes(5));
        assertThat(AuthService.lockDuration(4)).isEqualTo(Duration.ofMinutes(40));
        assertThat(AuthService.lockDuration(9)).isEqualTo(Duration.ofMinutes(1280));
        assertThat(AuthService.lockDuration(10)).isEqualTo(Duration.ofHours(24));
        assertThat(AuthService.lockDuration(64)).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void shouldRejectCorrectCodeWithoutEscalatingWhenAttemptsAlreadyExceeded() {
        // Counter is checked before comparing: even the correct code is rejected once the limit is passed,
        // and only the request that hit the limit escalates the lockout
        stubWrongCodeAttempt(4L);

        assertTooManyAttempts("123456");

        verify(redisService, never()).setOrThrow(eq("otp:email:lock"), anyString(), any(), any(Duration.class));
        verify(redisService, never()).incrementAndExpire(eq("otp:email:level"), anyString(), any(Duration.class));
        verify(userRepository, never()).findByEmail(anyString());
    }

    private void stubGuesses(long guesses) {
        when(redisService.incrementAndExpire(eq("otp:email:guesses"), startsWith(k("user@example.com") + ":"), any(Duration.class)))
                .thenReturn(guesses);
    }

    @Test
    void shouldExtendResendCooldownTo15MinutesWhenLastGuessAcrossIpsIsWrong() {
        stubWrongCodeAttempt(1L);
        stubGuesses(10L);

        assertThatThrownBy(() -> authService.verifyOtp("user@example.com", null, "999999", IP))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getMessageKey()).isEqualTo(MessageKey.AUTH_INVALID_OTP));

        verify(redisService).set(eq("otp:email:cooldown"), eq(k("user@example.com")), anyString(), eq(Duration.ofMinutes(15)));
        // Never deletes by identifier: a stale request must not remove an OTP resent meanwhile
        verify(redisService, never()).delete("otp:email", k("user@example.com"));
    }

    @Test
    void shouldRejectCorrectCodeWhenOtpGuessesExhaustedFromOtherIps() {
        stubWrongCodeAttempt(1L);
        stubGuesses(11L);

        assertThatThrownBy(() -> authService.verifyOtp("user@example.com", null, "123456", IP))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getMessageKey()).isEqualTo(MessageKey.AUTH_OTP_INVALID_OR_EXPIRED));

        verify(redisService, never()).delete("otp:email", k("user@example.com"));
        verify(redisService, never()).getAndDelete(anyString(), anyString(), any());
        verify(userRepository, never()).findByEmail(anyString());
    }

    @Test
    void shouldRejectCorrectCodeWhenConcurrentRequestAlreadyConsumedOtp() {
        stubWrongCodeAttempt(1L);
        when(redisService.getAndDelete("otp:email", k("user@example.com"), OtpRecord.class)).thenReturn(null);

        assertThatThrownBy(() -> authService.verifyOtp("user@example.com", null, "123456", IP))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getMessageKey()).isEqualTo(MessageKey.AUTH_OTP_INVALID_OR_EXPIRED));
        verify(userRepository, never()).findByEmail(anyString());
    }

    @Test
    void shouldRestoreResentOtpWhenOldCodeArrivesDuringResend() {
        stubWrongCodeAttempt(1L);
        OtpRecord resent = new OtpRecord(h("user@example.com", "777777"), "login", Instant.now().plusSeconds(1), 0);
        when(redisService.getAndDelete("otp:email", k("user@example.com"), OtpRecord.class)).thenReturn(resent);

        assertThatThrownBy(() -> authService.verifyOtp("user@example.com", null, "123456", IP))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getMessageKey()).isEqualTo(MessageKey.AUTH_OTP_INVALID_OR_EXPIRED));

        verify(redisService).set(eq("otp:email"), eq(k("user@example.com")), eq(resent), any(Duration.class));
        verify(userRepository, never()).findByEmail(anyString());
    }

    @Test
    void shouldNotLockOtherIpWhenOneIpIsLockedOut() {
        String otherIp = "198.51.100.9";
        assertThat(AuthService.attemptKey("user@example.com", otherIp)).isNotEqualTo(attempt("user@example.com"));
        lenient().when(redisService.hasKeyOrThrow("otp:email:lock", attempt("user@example.com"))).thenReturn(true);

        // No OTP stored for this test: reaching the lookup proves the other IP passed the lock check
        assertThatThrownBy(() -> authService.verifyOtp("user@example.com", null, "123456", otherIp))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getMessageKey()).isEqualTo(MessageKey.AUTH_OTP_INVALID_OR_EXPIRED));
    }

    @Test
    void shouldExtendFailCounterTtlWithoutResettingItWhenOtpResent() {
        when(userRepository.existsByEmail("user@example.com")).thenReturn(true);

        authService.sendOtp("user@example.com", null, null, IP);

        assertThat(storedOtpWrites()).contains(
                RedisService.RedisWrite.expire("otp:email:fail", attempt("user@example.com"), Duration.ofSeconds(180)));
        verify(redisService, never()).delete(eq("otp:email:fail"), anyString());
    }

    @Test
    void shouldRejectVerifyWhenIdentifierIsLockedOut() {
        when(redisService.hasKeyOrThrow("otp:email:lock", attempt("user@example.com"))).thenReturn(true);

        assertThatThrownBy(() -> authService.verifyOtp("user@example.com", null, "123456", IP))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getMessageKey()).isEqualTo(MessageKey.AUTH_OTP_TOO_MANY_ATTEMPTS));
        verify(redisService, never()).get(eq("otp:email"), anyString(), eq(OtpRecord.class));
    }

    @Test
    void verifyOtp_withValidCode_shouldReturnTokenAndDeleteOtp() {
        OtpRecord otpRecord = new OtpRecord(h("user@example.com", "123456"), "login", Instant.now(), 0);
        User user = User.builder().id(UUID.randomUUID()).email("user@example.com").displayName("Test User")
                .roles(new String[] { "user" }).build();

        when(redisService.get(eq("otp:email"), eq(k("user@example.com")), eq(OtpRecord.class))).thenReturn(otpRecord);
        when(redisService.getAndDelete("otp:email", k("user@example.com"), OtpRecord.class)).thenReturn(otpRecord);
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(jwtUtil.generateToken(anyString(), any(), anyBoolean())).thenReturn("mock-token");
        when(refreshTokenService.generate(anyString(), any(), anyBoolean())).thenReturn("mock-refresh-token");

        AuthResponse response = authService.verifyOtp("user@example.com", null, "123456", IP);

        assertThat(response.token()).isEqualTo("mock-token");
        assertThat(response.user().email()).isEqualTo("user@example.com");
        // A successful sign-in resets the failure counter and the lockout level for this identifier + IP
        verify(redisService).delete("otp:email:fail", attempt("user@example.com"));
        verify(redisService).delete("otp:email:level", attempt("user@example.com"));
    }

    // ── admin console login (P5-#13: check role before consuming OTP / creating account / sending email) ────────

    @Test
    void shouldReturn403WithoutConsumingOtpWhenAdminLoginByNonAdmin() {
        User customer = User.builder().id(UUID.randomUUID()).email("user@example.com")
                .displayName("Customer").roles(new String[] { "user" }).build();
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(customer));

        assertThatThrownBy(() -> authService.verifyAdminOtp("user@example.com", "123456", IP))
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
    void shouldReturn403WithoutConsumingOtpWhenAdminLoginHasNoAccount() {
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.verifyAdminOtp("ghost@example.com", "123456", IP))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getMessageKey())
                        .isEqualTo(MessageKey.AUTH_ADMIN_FORBIDDEN));

        verify(redisService, org.mockito.Mockito.never()).get(anyString(), anyString(), any(Class.class));
        verify(userRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void shouldNotSendOtpWhenAdminSendOtpByNonAdmin() {
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.sendAdminOtp("user@example.com", IP))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getMessageKey())
                        .isEqualTo(MessageKey.AUTH_ADMIN_FORBIDDEN));

        verify(redisService, never()).execInTransaction(any());
        verify(emailService, never()).sendAdminLoginEmail(anyString(), anyString());
    }

    @Test
    void shouldIssueAdminScopeFromAdminNamespaceWhenAdminLoginSucceeds() {
        // The admin OTP lives in its own namespace, so a consumer-login code cannot be redeemed here
        OtpRecord otpRecord = new OtpRecord(h("admin@example.com", "123456"), "login", Instant.now(), 0);
        User admin = User.builder().id(UUID.randomUUID()).email("admin@example.com")
                .displayName("Admin").roles(new String[] { "user", "admin" }).active(true).build();
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
        when(redisService.get(eq("otp:admin-email"), eq(k("admin@example.com")), eq(OtpRecord.class))).thenReturn(otpRecord);
        when(redisService.getAndDelete("otp:admin-email", k("admin@example.com"), OtpRecord.class)).thenReturn(otpRecord);
        when(jwtUtil.generateToken(anyString(), any(), eq(true))).thenReturn("admin-token");
        when(refreshTokenService.generate(anyString(), any(), eq(true))).thenReturn("admin-refresh");

        AuthResponse response = authService.verifyAdminOtp("admin@example.com", "123456", IP);

        assertThat(response.token()).isEqualTo("admin-token");
        assertThat(response.refreshToken()).isEqualTo("admin-refresh");
    }

    @Test
    void shouldIssueUserScopeWhenAdminSignsInThroughConsumerLogin() {
        OtpRecord otpRecord = new OtpRecord(h("admin@example.com", "123456"), "login", Instant.now(), 0);
        User admin = User.builder().id(UUID.randomUUID()).email("admin@example.com")
                .displayName("Admin").roles(new String[] { "user", "admin" }).active(true).build();
        when(redisService.get(eq("otp:email"), eq(k("admin@example.com")), eq(OtpRecord.class))).thenReturn(otpRecord);
        when(redisService.getAndDelete("otp:email", k("admin@example.com"), OtpRecord.class)).thenReturn(otpRecord);
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
        when(jwtUtil.generateToken(anyString(), any(), eq(false))).thenReturn("user-token");
        when(refreshTokenService.generate(anyString(), any(), eq(false))).thenReturn("user-refresh");

        AuthResponse response = authService.verifyOtp("admin@example.com", null, "123456", IP);

        assertThat(response.token()).isEqualTo("user-token");
    }

    // ── admin unlock ─────────────────────────────────────────────────

    @Test
    void shouldIndexAttemptKeyWhenCodeIsWrong() {
        OtpRecord otpRecord = new OtpRecord(h("user@example.com", "123456"), "login", Instant.now(), 0);
        when(redisService.get(eq("otp:email"), eq(k("user@example.com")), eq(OtpRecord.class))).thenReturn(otpRecord);
        when(redisService.incrementAndExpire(eq("otp:email:fail"), eq(attempt("user@example.com")), any(Duration.class)))
                .thenReturn(1L);

        assertThatThrownBy(() -> authService.verifyOtp("user@example.com", null, "654321", IP))
                .isInstanceOf(ApiException.class);

        verify(redisService).execInTransaction(List.of(
                RedisService.RedisWrite.hput("otp:email:attempt-idx", k("user@example.com"), attempt("user@example.com"), "1"),
                RedisService.RedisWrite.expire("otp:email:attempt-idx", k("user@example.com"), Duration.ofHours(48))));
    }

    @Test
    void shouldClearEveryTargetAndDiscardOtpWhenUnlockGivesIp() {
        Instant created = Instant.now();
        List<String> key = List.of(attempt("user@example.com"));
        when(redisService.deleteAll(anyString(), any())).thenReturn(1L);
        when(redisService.getAndDelete("otp:email", k("user@example.com"), OtpRecord.class))
                .thenReturn(new OtpRecord(h("user@example.com", "123456"), "login", created, 0));

        int cleared = authService.unlockLogin("email", "user@example.com", IP, Set.of("lock", "attempts", "cooldown"));

        // lock + level + fail + OTP + guesses + cooldown
        assertThat(cleared).isEqualTo(6);
        verify(redisService).deleteAll("otp:email:lock", key);
        verify(redisService).deleteAll("otp:email:level", key);
        verify(redisService).deleteAll("otp:email:fail", key);
        verify(redisService).deleteAll("otp:email:guesses", List.of(k("user@example.com") + ":" + created.toEpochMilli()));
        verify(redisService).deleteAll("otp:email:cooldown", List.of(k("user@example.com")));
        verify(redisService).hDelete("otp:email:attempt-idx", k("user@example.com"), key.get(0));
        verify(redisService, never()).hGetAll(anyString(), anyString());
    }

    @Test
    void shouldAlsoClearCounterWhenUnlockClearsOnlyLock() {
        // A lock removed with its counter still at 3 would keep refusing the right code
        when(redisService.hGetAll("otp:phone:attempt-idx", k("+886912345678")))
                .thenReturn(java.util.Map.of("k1", "1", "k2", "1"));
        when(redisService.deleteAll(anyString(), any())).thenReturn(2L);

        int cleared = authService.unlockLogin("phone", "+886912345678", null, Set.of("lock"));

        assertThat(cleared).isEqualTo(6);
        verify(redisService).deleteAll(eq("otp:phone:lock"), argThat(keys -> Set.copyOf(keys).equals(Set.of("k1", "k2"))));
        verify(redisService).deleteAll(eq("otp:phone:fail"), argThat(keys -> keys.size() == 2));
        // The OTP and its guess budget are left alone: only `attempts` discards them
        verify(redisService, never()).getAndDelete(eq("otp:phone"), anyString(), any());
        verify(redisService, never()).deleteAll(eq("otp:phone:cooldown"), any());
        verify(redisService).delete("otp:phone:attempt-idx", k("+886912345678"));
    }

    @Test
    void shouldHitSameAttemptKeyWhenIpIsSpelledDifferently() {
        assertThat(AuthService.attemptKey("a@b.c", "2001:DB8::1"))
                .isEqualTo(AuthService.attemptKey("a@b.c", "2001:0db8:0:0:0:0:0:1"));
        assertThat(AuthService.attemptKey("a@b.c", " 203.0.113.7")).isEqualTo(AuthService.attemptKey("a@b.c", "203.0.113.7"));
        assertThat(AuthService.canonicalIp("not-an-ip")).isEqualTo("not-an-ip");
    }

    @Test
    void shouldRefuseSuspendedAdminBeforeSendingOtp() {
        User admin = User.builder().id(UUID.randomUUID()).email("admin@example.com")
                .roles(new String[] { "user", "admin" }).active(false).build();
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> authService.sendAdminOtp("admin@example.com", IP))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.AUTH_ACCOUNT_BANNED));
        verify(emailService, never()).sendAdminLoginEmail(anyString(), anyString());
    }

    @Test
    void shouldRejectLockedAdminLoginWithoutDatabaseLookup() {
        when(redisService.hasKeyOrThrow("otp:admin-email:lock", attempt("admin@example.com"))).thenReturn(true);

        assertThatThrownBy(() -> authService.verifyAdminOtp("admin@example.com", "123456", IP))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.AUTH_OTP_TOO_MANY_ATTEMPTS));
        verify(userRepository, never()).findByEmail(anyString());
    }

    @Test
    void shouldSendLoginEmailWithoutSignupLookupWhenAdminRequestsOtp() {
        User admin = User.builder().id(UUID.randomUUID()).email("admin@example.com")
                .roles(new String[] { "admin" }).active(true).build();
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
        when(redisService.setIfAbsent(eq("otp:admin-email:cooldown"), eq(k("admin@example.com")), any(), any())).thenReturn(true);

        authService.sendAdminOtp("admin@example.com", IP);

        verify(emailService).sendAdminLoginEmail(eq("admin@example.com"), anyString());
        verify(userRepository, never()).existsByEmail(anyString());
    }

    // ── consumeOtp constant-time comparison (P5-#19) ─────────────────────────────────

    @Test
    void verifyOtp_wrongCodeSameLength_shouldRejectViaConstantTimeCompare() {
        // Wrong code of the same length is still rejected (constant-time comparison does not affect correctness)
        OtpRecord otpRecord = new OtpRecord(h("user@example.com", "123456"), "login", Instant.now(), 0);
        when(redisService.get(eq("otp:email"), eq(k("user@example.com")), eq(OtpRecord.class))).thenReturn(otpRecord);
        when(redisService.incrementAndExpire(eq("otp:email:fail"), eq(attempt("user@example.com")), any(Duration.class)))
                .thenReturn(1L);

        assertThatThrownBy(() -> authService.verifyOtp("user@example.com", null, "654321", IP))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getMessageKey()).isEqualTo(MessageKey.AUTH_INVALID_OTP));

        // A wrong code must not delete the OTP (retries still allowed within the limit)
        verify(redisService, org.mockito.Mockito.never()).delete("otp:email", k("user@example.com"));
    }

    // ── refresh ──────────────────────────────────────────────────────

    @Test
    void refresh_returnsAccessAndRefreshTokenFromRotateResult() {
        RefreshTokenService.RefreshTokenData data = new RefreshTokenService.RefreshTokenData(
                "user-x", List.of("user"), "session-1", false);
        RefreshTokenService.RotateResult rotateResult = new RefreshTokenService.RotateResult(
                "new-access", "new-refresh", data);
        when(refreshTokenService.rotate("old-refresh")).thenReturn(rotateResult);

        AuthResponse response = authService.refresh("old-refresh");

        assertThat(response.token()).isEqualTo("new-access");
        assertThat(response.refreshToken()).isEqualTo("new-refresh");
        // AuthService.refresh must not sign the access token itself; it comes from RotateResult
        verify(jwtUtil, org.mockito.Mockito.never()).generateToken(anyString(), any(), anyBoolean());
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

        User result = authService.findOrCreateUserByEmail("user@example.com", "User", "google", true).user();

        assertThat(result).isSameAs(existing);
        verify(userRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void findOrCreateUserByEmail_newEmailAndVerified_shouldCreateUser() {
        // Email verified and account does not exist -> create a new account
        when(userRepository.findByEmail("fresh@example.com")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User result = authService.findOrCreateUserByEmail("fresh@example.com", "Fresh", "google", true).user();

        assertThat(result).isNotNull();
        assertThat(result.getEmail()).isEqualTo("fresh@example.com");
        assertThat(result.getDisplayName()).isEqualTo("Fresh");
        assertThat(result.getAuthMethod()).isEqualTo("google");
        // username is generated from the email local part: <local>_<4 digits>
        assertThat(result.getUsername()).matches("fresh_\\d{4}");
        assertThat(result.getRoles()).containsExactly("user");
        assertThat(result.isActive()).isTrue();
        // A random cast member, never bot:none (that is only ever the user's own choice)
        assertThat(result.getAvatarUrl()).startsWith("bot:").isNotEqualTo("bot:none");
        assertThat(com.vomatt.users.BotAvatar.KINDS).contains(result.getAvatarUrl().substring("bot:".length()));
        verify(userRepository).save(any(User.class));
    }

    @Test
    void findOrCreateUserByEmail_usernameCollision_shouldRetryUntilUnique() {
        when(userRepository.findByEmail("fresh@example.com")).thenReturn(Optional.empty());
        // The first generated username is taken; the second one is available
        when(userRepository.existsByUsername(anyString())).thenReturn(true, false);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User result = authService.findOrCreateUserByEmail("fresh@example.com", null, "email_otp", true).user();

        verify(userRepository, org.mockito.Mockito.times(2)).existsByUsername(anyString());
        assertThat(result.getUsername()).matches("fresh_\\d{4}");
        // When fullName is empty, displayName falls back to the email local part
        assertThat(result.getDisplayName()).isEqualTo("fresh");
    }

    @Test
    void shouldNeverGenerateReservedUsernameWhenEmailLocalPartIsReserved() {
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User result = authService.findOrCreateUserByEmail("admin@example.com", null, "email_otp", true).user();

        assertThat(result.getUsername()).matches("admin_\\d{4}");
        assertThat(ReservedUsernames.contains(result.getUsername())).isFalse();
    }

    // ── Email Invitee binding: only a verified email turns email Invitees into user Invitees ────────────

    @Test
    void shouldBindEmailInviteesWhenEmailOtpVerified() {
        OtpRecord otpRecord = new OtpRecord(h("carol@example.com", "123456"), "signup", Instant.now(), 0);
        when(redisService.get(eq("otp:email"), eq(k("carol@example.com")), eq(OtpRecord.class))).thenReturn(otpRecord);
        when(redisService.getAndDelete("otp:email", k("carol@example.com"), OtpRecord.class)).thenReturn(otpRecord);
        when(userRepository.findByEmail("carol@example.com")).thenReturn(Optional.empty());
        UUID carolId = UUID.randomUUID();
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User saved = inv.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", carolId);
            return saved;
        });
        when(jwtUtil.generateToken(anyString(), any(), anyBoolean())).thenReturn("mock-token");
        when(refreshTokenService.generate(anyString(), any(), anyBoolean())).thenReturn("mock-refresh-token");

        assertThat(authService.verifyOtp(" Carol@Example.com", null, "123456", IP).isNewUser()).isTrue();

        var order = inOrder(userRepository, voteService, inviteListService);
        order.verify(userRepository).save(any(User.class));
        order.verify(voteService).bindEmailInvitees(argThat(u -> carolId.equals(u.getId()) && "carol@example.com".equals(u.getEmail())));
        // Polls are locked before Invite Lists
        order.verify(inviteListService).bindEmailMembers(argThat(u -> carolId.equals(u.getId())));
        verify(transactionTemplate).execute(any());
    }

    @Test
    void shouldResolveSameAccountWhenGmailVariantVerifiesOtp() {
        User existing = User.builder().id(UUID.randomUUID()).email("ab@gmail.com")
                .roles(new String[] { "user" }).build();
        OtpRecord gmailOtp = new OtpRecord(h("ab@gmail.com", "123456"), "login", Instant.now(), 0);
        when(redisService.get(eq("otp:email"), eq(k("ab@gmail.com")), eq(OtpRecord.class))).thenReturn(gmailOtp);
        when(redisService.getAndDelete(eq("otp:email"), eq(k("ab@gmail.com")), eq(OtpRecord.class))).thenReturn(gmailOtp);
        when(userRepository.findByEmail("ab@gmail.com")).thenReturn(Optional.of(existing));
        when(jwtUtil.generateToken(anyString(), any(), anyBoolean())).thenReturn("mock-token");
        when(refreshTokenService.generate(anyString(), any(), anyBoolean())).thenReturn("mock-refresh-token");

        AuthResponse response = authService.verifyOtp("A.B+x@gmail.com", null, "123456", IP);

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
        when(jwtUtil.generateToken(anyString(), any(), anyBoolean())).thenReturn("mock-token");
        when(refreshTokenService.generate(anyString(), any(), anyBoolean())).thenReturn("mock-refresh-token");

        authService.googleLogin(idToken);

        verify(voteService).bindEmailInvitees(carol);
        verify(inviteListService).bindEmailMembers(carol);
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
        when(jwtUtil.generateToken(anyString(), any(), anyBoolean())).thenReturn("mock-token");
        when(refreshTokenService.generate(anyString(), any(), anyBoolean())).thenReturn("mock-refresh-token");

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
        verify(inviteListService, never()).bindEmailMembers(any());
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
        stubGoogleUser();

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
        claims.put("exp", Instant.now().minusSeconds(120).getEpochSecond());

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
    void shouldRefetchJwksWhenKidMissingFromCachedKeys() throws Exception {
        ReflectionTestUtils.setField(authService, "googleClientId", "client-id");
        stubGoogleUser();
        String idToken = signedGoogleToken(googlePayload("carol@example.com", true));
        // Cached JWKS (5 minutes old, still within the TTL) predates the key rotation and lacks kid k1
        seedJwksCache("https://www.googleapis.com/oauth2/v3/certs",
                java.util.List.of(java.util.Map.of("kid", "rotated-out", "n", "AQAB", "e", "AQAB")), Instant.now().minusSeconds(300));

        assertThat(authService.googleLogin(idToken).token()).isEqualTo("mock-token");
        verify(restClient, org.mockito.Mockito.times(1)).get();
    }

    @Test
    void shouldNotRefetchJwksWhenKidMissingWithinCooldown() throws Exception {
        ReflectionTestUtils.setField(authService, "googleClientId", "client-id");
        String idToken = signGoogleToken(googlePayload("carol@example.com", true), rsaKeyPair());
        seedJwksCache("https://www.googleapis.com/oauth2/v3/certs",
                java.util.List.of(java.util.Map.of("kid", "rotated-out", "n", "AQAB", "e", "AQAB")), Instant.now());

        assertGoogleRejected(idToken, MessageKey.AUTH_GOOGLE_NO_EMAIL);
        verify(restClient, never()).get();
    }

    @Test
    void shouldAcceptGoogleTokenWhenExpiredWithinClockSkew() throws Exception {
        ReflectionTestUtils.setField(authService, "googleClientId", "client-id");
        stubGoogleUser();
        var claims = googlePayload("carol@example.com", true);
        claims.put("exp", Instant.now().minusSeconds(30).getEpochSecond());

        assertThat(authService.googleLogin(signedGoogleToken(claims)).token()).isEqualTo("mock-token");
    }

    @Test
    void shouldRejectAppleTokenWhenAlgIsNotRs256() {
        stubJwksFetch(java.util.Map.of("keys", java.util.List.of(java.util.Map.of("kid", "k1", "n", "AQAB", "e", "AQAB"))), null);
        String idToken = base64Url("{\"alg\":\"none\",\"kid\":\"k1\"}") + "."
                + base64Url("{\"iss\":\"https://appleid.apple.com\"}") + ".c2ln";

        assertThatThrownBy(() -> authService.appleLogin(idToken, null, "raw-nonce"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getMessageKey()).isEqualTo(MessageKey.AUTH_APPLE_INVALID_TOKEN));
    }

    private void stubGoogleUser() {
        User carol = User.builder().id(UUID.randomUUID()).email("carol@example.com").displayName("Carol")
                .roles(new String[] { "user" }).build();
        when(userRepository.findByEmail("carol@example.com")).thenReturn(Optional.of(carol));
        when(jwtUtil.generateToken(anyString(), any(), anyBoolean())).thenReturn("mock-token");
        when(refreshTokenService.generate(anyString(), any(), anyBoolean())).thenReturn("mock-refresh-token");
    }

    @SuppressWarnings("unchecked")
    private void seedJwksCache(String url, java.util.List<java.util.Map<String, Object>> keys, Instant fetchedAt) throws Exception {
        var ctor = Class.forName("com.vomatt.auth.AuthService$JwksCache").getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        ((java.util.Map<String, Object>) ReflectionTestUtils.getField(authService, "jwksCache"))
                .put(url, ctor.newInstance(keys, fetchedAt));
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

    @Test
    void shouldRejectGoogleTokenWhenPresentedTwice() throws Exception {
        ReflectionTestUtils.setField(authService, "googleClientId", "client-id");
        String idToken = signedGoogleToken(googlePayload("carol@example.com", true));
        when(redisService.setIfAbsent(eq("oauth:used"), eq(com.vomatt.common.security.AppKey.sha256Hex(idToken)), eq("1"), any(Duration.class)))
                .thenReturn(false);

        assertGoogleRejected(idToken, MessageKey.AUTH_OAUTH_TOKEN_REPLAYED);
        verify(userRepository, never()).findByEmail(anyString());
    }

    @Test
    void shouldRejectAppleTokenWhenNonceDoesNotMatch() throws Exception {
        java.security.KeyPair kp = rsaKeyPair();
        stubJwksFetch(jwksOf((java.security.interfaces.RSAPublicKey) kp.getPublic()), null);
        java.util.Map<String, Object> claims = new java.util.HashMap<>();
        claims.put("iss", "https://appleid.apple.com");
        claims.put("aud", "com.vomatt.app");
        claims.put("exp", Instant.now().plusSeconds(600).getEpochSecond());
        claims.put("email", "carol@example.com");
        claims.put("email_verified", "true");
        claims.put("nonce", com.vomatt.common.security.AppKey.sha256Hex("raw-nonce"));
        String idToken = signGoogleToken(claims, kp);

        assertThatThrownBy(() -> authService.appleLogin(idToken, null, "another-nonce"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getMessageKey()).isEqualTo(MessageKey.AUTH_APPLE_INVALID_NONCE));
        // A missing claim is a mismatch too
        claims.remove("nonce");
        String noNonce = signGoogleToken(claims, kp);
        assertThatThrownBy(() -> authService.appleLogin(noNonce, null, "raw-nonce"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getMessageKey()).isEqualTo(MessageKey.AUTH_APPLE_INVALID_NONCE));
        verify(redisService, never()).setIfAbsent(eq("oauth:used"), anyString(), any(), any(Duration.class));

        // Matching nonce: signs in, and the token is consumed
        User carol = User.builder().id(UUID.randomUUID()).email("carol@example.com").roles(new String[] { "user" }).build();
        when(userRepository.findByEmail("carol@example.com")).thenReturn(Optional.of(carol));
        when(jwtUtil.generateToken(anyString(), any(), anyBoolean())).thenReturn("mock-token");
        when(refreshTokenService.generate(anyString(), any(), anyBoolean())).thenReturn("mock-refresh-token");
        assertThat(authService.appleLogin(idToken, null, "raw-nonce").token()).isEqualTo("mock-token");
        // The marker outlives the token: exp (600 s) plus the 60 s clock skew
        verify(redisService).setIfAbsent(eq("oauth:used"), eq(com.vomatt.common.security.AppKey.sha256Hex(idToken)), eq("1"),
                argThat((Duration ttl) -> ttl.compareTo(Duration.ofMinutes(10)) > 0 && ttl.compareTo(Duration.ofMinutes(11)) <= 0));
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

        User result = authService.findOrCreateUserByPhone("0912345678").user();

        assertThat(result.getPhoneNumber()).isEqualTo("+886912345678");
        assertThat(result.getEmail()).isNull();
        assertThat(result.getUsername()).matches("user_\\d{4}");
        assertThat(result.getDisplayName()).isEqualTo("+886912345678");
        // A random cast member, never bot:none (that is only ever the user's own choice)
        assertThat(result.getAvatarUrl()).startsWith("bot:").isNotEqualTo("bot:none");
        assertThat(com.vomatt.users.BotAvatar.KINDS).contains(result.getAvatarUrl().substring("bot:".length()));
    }

    @Test
    void shouldResolveSameAccountWhenOtpSentToLocalFormAndVerifiedAsE164() {
        User existing = User.builder().id(UUID.randomUUID()).phoneNumber("+886912345678")
                .displayName("Phone User").roles(new String[] { "user" }).build();
        when(userRepository.existsByPhoneNumber("+886912345678")).thenReturn(true);
        when(userRepository.findByPhoneNumber("+886912345678")).thenReturn(Optional.of(existing));

        authService.sendOtp(null, "0912345678", "login", IP);

        OtpRecord sent = storedOtp("otp:phone", k("+886912345678"));
        org.mockito.ArgumentCaptor<String> code = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(smsService).sendOtp(eq("+886912345678"), code.capture());
        assertThat(sent.codeHash()).isEqualTo(h("+886912345678", code.getValue()));

        when(redisService.get(eq("otp:phone"), eq(k("+886912345678")), eq(OtpRecord.class))).thenReturn(sent);
        when(redisService.getAndDelete("otp:phone", k("+886912345678"), OtpRecord.class)).thenReturn(sent);
        when(jwtUtil.generateToken(anyString(), any(), anyBoolean())).thenReturn("access-token");
        when(refreshTokenService.generate(anyString(), any(), anyBoolean())).thenReturn("refresh-token");

        AuthResponse response = authService.verifyOtp(null, "+886912345678", code.getValue(), IP);

        assertThat(response.user().id()).isEqualTo(existing.getId().toString());
        assertThat(response.user().phoneNumber()).isEqualTo("+886912345678");
        assertThat(authService.findOrCreateUserByPhone("0912345678").user()).isSameAs(existing);
    }

    // ── buildAuthResponse: active=false (suspended) check ───────────────────────────

    @Test
    void buildAuthResponse_inactiveUser_shouldThrowForbidden() {
        User banned = User.builder().id(UUID.randomUUID()).email("banned@example.com")
                .displayName("Banned").roles(new String[] { "user" }).active(false).build();

        assertThatThrownBy(() -> authService.buildAuthResponse(banned, false, false))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getMessageKey())
                        .isEqualTo(MessageKey.AUTH_ACCOUNT_BANNED));

        verify(jwtUtil, org.mockito.Mockito.never()).generateToken(anyString(), any(), anyBoolean());
        verify(userRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void buildAuthResponse_activeUser_shouldReturnAuthResponse() {
        User user = User.builder().id(UUID.randomUUID()).email("ok@example.com").username("ok_user")
                .displayName("OK User").roles(new String[] { "user" }).active(true)
                .build();
        when(jwtUtil.generateToken(anyString(), any(), anyBoolean())).thenReturn("ok-token");
        when(refreshTokenService.generate(anyString(), any(), anyBoolean())).thenReturn("ok-refresh");

        AuthResponse response = authService.buildAuthResponse(user, false, false);

        assertThat(response.token()).isEqualTo("ok-token");
        assertThat(response.refreshToken()).isEqualTo("ok-refresh");
        assertThat(response.user().email()).isEqualTo("ok@example.com");
        assertThat(response.user().username()).isEqualTo("ok_user");
        assertThat(response.user().roles()).containsExactly("user");
        // Successful login updates lastLoginAt; an existing account is never reported as new
        assertThat(user.getLastLoginAt()).isNotNull();
        assertThat(response.isNewUser()).isFalse();
        verify(userRepository).save(user);
    }

    // ── appleLogin (P6-#16 external failure classification + #21 JWKS cache) ──────────────────

    @Test
    void appleLogin_whenJwksBodyNull_shouldThrowExternalServiceUnavailableNot401() {
        // JWKS response body is null -> treated as an upstream error -> 503, not a misleading 401 login failure
        stubJwksFetch(null, null);

        assertThatThrownBy(() -> authService.appleLogin("a.b.c", null, "raw-nonce"))
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

        assertThatThrownBy(() -> authService.appleLogin("a.b.c", null, "raw-nonce"))
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

        assertThatThrownBy(() -> authService.appleLogin("a.b.c", null, "raw-nonce"))
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

        assertThatThrownBy(() -> authService.appleLogin("a.b.c", null, "raw-nonce"))
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

        assertThatThrownBy(() -> authService.appleLogin(idToken, null, "raw-nonce"))
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

        assertThatThrownBy(() -> authService.appleLogin(idToken, null, "raw-nonce")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> authService.appleLogin(idToken, null, "raw-nonce")).isInstanceOf(ApiException.class);

        // JWKS fetched only once (second call hits the cache)
        verify(restClient, org.mockito.Mockito.times(1)).get();
    }

    @Test
    void shouldThrowNotConfiguredWhenAppleClientIdIsBlank() {
        ReflectionTestUtils.setField(authService, "appleClientId", "");

        assertThatThrownBy(() -> authService.appleLogin("a.b.c", null, "raw-nonce"))
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

        assertThatThrownBy(() -> authService.appleLogin(idToken, null, "raw-nonce"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getMessageKey()).isEqualTo(MessageKey.AUTH_APPLE_TOKEN_EXPIRED));
        verify(userRepository, never()).findByEmail(anyString());
    }

    private static String base64Url(String json) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }


    // ── lineLogin ────────────────────────────────────────────────────

    private static final String LINE_TOKEN_URL = "https://api.line.me/oauth2/v2.1/token";
    private static final String LINE_VERIFY_URL = "https://api.line.me/oauth2/v2.1/verify";
    private static final String LINE_PROFILE_URL = "https://api.line.me/v2/profile";

    /** Swaps the mocked RestClient for a real one bound to a mock LINE server. */
    private org.springframework.test.web.client.MockRestServiceServer lineServer() {
        ReflectionTestUtils.setField(authService, "lineChannelId", "channel-id");
        ReflectionTestUtils.setField(authService, "lineChannelSecret", "channel-secret");
        ReflectionTestUtils.setField(authService, "lineTokenUrl", LINE_TOKEN_URL);
        ReflectionTestUtils.setField(authService, "lineVerifyUrl", LINE_VERIFY_URL);
        ReflectionTestUtils.setField(authService, "lineProfileUrl", LINE_PROFILE_URL);
        var builder = org.springframework.web.client.RestClient.builder();
        var server = org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        ReflectionTestUtils.setField(authService, "restClient", builder.build());
        return server;
    }

    private static org.springframework.test.web.client.response.DefaultResponseCreator lineJson(String json) {
        return org.springframework.test.web.client.response.MockRestResponseCreators
                .withSuccess(json, org.springframework.http.MediaType.APPLICATION_JSON);
    }

    private static void assertApiError(Throwable ex, org.springframework.http.HttpStatus status, MessageKey key) {
        assertThat(ex).isInstanceOf(ApiException.class);
        assertThat(((ApiException) ex).getStatus()).isEqualTo(status);
        assertThat(((ApiException) ex).getMessageKey()).isEqualTo(key);
    }

    @Test
    void shouldSignInWithVerifiedEmailWhenLineLoginSucceeds() {
        var server = lineServer();
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(LINE_TOKEN_URL))
                .andRespond(lineJson("{\"access_token\":\"at\",\"id_token\":\"it\"}"));
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(LINE_PROFILE_URL))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.header("Authorization", "Bearer at"))
                .andRespond(lineJson("{\"userId\":\"U123\",\"displayName\":\"Dave\"}"));
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(LINE_VERIFY_URL))
                .andRespond(lineJson("{\"email\":\"Dave@Example.com\"}"));
        User dave = User.builder().id(UUID.randomUUID()).email("dave@example.com").displayName("Dave")
                .roles(new String[] { "user" }).build();
        when(userRepository.findByEmail("dave@example.com")).thenReturn(Optional.of(dave));
        when(jwtUtil.generateToken(anyString(), any(), anyBoolean())).thenReturn("mock-token");
        when(refreshTokenService.generate(anyString(), any(), anyBoolean())).thenReturn("mock-refresh-token");

        AuthResponse response = authService.lineLogin("code", "https://app.example.com/cb");

        assertThat(response.user().id()).isEqualTo(dave.getId().toString());
        server.verify();
    }

    @Test
    void shouldThrow401TokenExchangeFailedWhenLineTokenEndpointReturns4xx() {
        var server = lineServer();
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(LINE_TOKEN_URL))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest());

        assertApiError(org.assertj.core.api.Assertions.catchThrowable(() -> authService.lineLogin("bad", "https://app.example.com/cb")),
                org.springframework.http.HttpStatus.UNAUTHORIZED, MessageKey.AUTH_LINE_TOKEN_EXCHANGE_FAILED);
        server.verify();
    }

    @Test
    void shouldThrow503WhenLineTokenEndpointReturns5xx() {
        var server = lineServer();
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(LINE_TOKEN_URL))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withServerError());

        assertApiError(org.assertj.core.api.Assertions.catchThrowable(() -> authService.lineLogin("code", "https://app.example.com/cb")),
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, MessageKey.COMMON_SERVICE_UNAVAILABLE);
        server.verify();
    }

    @Test
    void shouldThrow503WhenLineTokenEndpointIsUnreachable() {
        var server = lineServer();
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(LINE_TOKEN_URL))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withException(new java.net.SocketTimeoutException("Read timed out")));

        assertApiError(org.assertj.core.api.Assertions.catchThrowable(() -> authService.lineLogin("code", "https://app.example.com/cb")),
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, MessageKey.COMMON_SERVICE_UNAVAILABLE);
        server.verify();
    }

    @Test
    void shouldThrow401ProfileFailedWhenLineProfileEndpointReturns4xx() {
        var server = lineServer();
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(LINE_TOKEN_URL))
                .andRespond(lineJson("{\"access_token\":\"at\"}"));
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(LINE_PROFILE_URL))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest());

        assertApiError(org.assertj.core.api.Assertions.catchThrowable(() -> authService.lineLogin("code", "https://app.example.com/cb")),
                org.springframework.http.HttpStatus.UNAUTHORIZED, MessageKey.AUTH_LINE_PROFILE_FAILED);
        server.verify();
    }

    @Test
    void shouldThrow503WhenLineProfileEndpointReturns5xx() {
        var server = lineServer();
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(LINE_TOKEN_URL))
                .andRespond(lineJson("{\"access_token\":\"at\"}"));
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(LINE_PROFILE_URL))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withStatus(org.springframework.http.HttpStatus.BAD_GATEWAY));

        assertApiError(org.assertj.core.api.Assertions.catchThrowable(() -> authService.lineLogin("code", "https://app.example.com/cb")),
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, MessageKey.COMMON_SERVICE_UNAVAILABLE);
        server.verify();
    }


    /** Expects the token and profile calls to succeed, the token carrying an id_token so the verify call follows. */
    private org.springframework.test.web.client.MockRestServiceServer lineServerUpToVerify() {
        var server = lineServer();
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(LINE_TOKEN_URL))
                .andRespond(lineJson("{\"access_token\":\"at\",\"id_token\":\"it\"}"));
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(LINE_PROFILE_URL))
                .andRespond(lineJson("{\"userId\":\"U123\",\"displayName\":\"Dave\"}"));
        return server;
    }

    @Test
    void shouldThrow503WhenLineTokenEndpointRateLimits() {
        var server = lineServer();
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(LINE_TOKEN_URL))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withTooManyRequests());

        assertApiError(org.assertj.core.api.Assertions.catchThrowable(() -> authService.lineLogin("code", "https://app.example.com/cb")),
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, MessageKey.COMMON_SERVICE_UNAVAILABLE);
        server.verify();
    }

    @Test
    void shouldThrow503WhenLineProfileEndpointReturns408() {
        var server = lineServer();
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(LINE_TOKEN_URL))
                .andRespond(lineJson("{\"access_token\":\"at\"}"));
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(LINE_PROFILE_URL))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withStatus(org.springframework.http.HttpStatus.REQUEST_TIMEOUT));

        assertApiError(org.assertj.core.api.Assertions.catchThrowable(() -> authService.lineLogin("code", "https://app.example.com/cb")),
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, MessageKey.COMMON_SERVICE_UNAVAILABLE);
        server.verify();
    }

    @Test
    void shouldThrow503WithoutCreatingUserWhenLineVerifyEndpointReturns5xx() {
        var server = lineServerUpToVerify();
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(LINE_VERIFY_URL))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withServerError());

        assertApiError(org.assertj.core.api.Assertions.catchThrowable(() -> authService.lineLogin("code", "https://app.example.com/cb")),
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, MessageKey.COMMON_SERVICE_UNAVAILABLE);
        server.verify();
        verify(userRepository, never()).save(any());
    }

    @Test
    void shouldThrow503WithoutCreatingUserWhenLineVerifyEndpointIsUnreachable() {
        var server = lineServerUpToVerify();
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(LINE_VERIFY_URL))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withException(new java.net.SocketTimeoutException("Read timed out")));

        assertApiError(org.assertj.core.api.Assertions.catchThrowable(() -> authService.lineLogin("code", "https://app.example.com/cb")),
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, MessageKey.COMMON_SERVICE_UNAVAILABLE);
        server.verify();
        verify(userRepository, never()).save(any());
    }

    @Test
    void shouldUseSyntheticEmailWhenLineVerifyReturnsNoEmail() {
        var server = lineServerUpToVerify();
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(LINE_VERIFY_URL))
                .andRespond(lineJson("{\"sub\":\"U123\"}"));
        User dave = User.builder().id(UUID.randomUUID()).email("line_u123@line.oauth").displayName("Dave")
                .roles(new String[] { "user" }).build();
        when(userRepository.findByEmail("line_u123@line.oauth")).thenReturn(Optional.of(dave));
        when(jwtUtil.generateToken(anyString(), any(), anyBoolean())).thenReturn("mock-token");
        when(refreshTokenService.generate(anyString(), any(), anyBoolean())).thenReturn("mock-refresh-token");

        AuthResponse response = authService.lineLogin("code", "https://app.example.com/cb");

        assertThat(response.user().id()).isEqualTo(dave.getId().toString());
        server.verify();
    }

    @Test
    void shouldThrow401TokenExchangeFailedWhenLineAccessTokenIsNotAString() {
        var server = lineServer();
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(LINE_TOKEN_URL))
                .andRespond(lineJson("{\"access_token\":42}"));

        assertApiError(org.assertj.core.api.Assertions.catchThrowable(() -> authService.lineLogin("code", "https://app.example.com/cb")),
                org.springframework.http.HttpStatus.UNAUTHORIZED, MessageKey.AUTH_LINE_TOKEN_EXCHANGE_FAILED);
        server.verify();
    }


    /** Stubs the user lookup and token issuing for a LINE sign-in that ends on {@code email}. */
    private User existingLineUser(String email) {
        User user = User.builder().id(UUID.randomUUID()).email(email).displayName("Dave")
                .roles(new String[] { "user" }).build();
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));
        when(jwtUtil.generateToken(anyString(), any(), anyBoolean())).thenReturn("mock-token");
        when(refreshTokenService.generate(anyString(), any(), anyBoolean())).thenReturn("mock-refresh-token");
        return user;
    }

    @Test
    void shouldUseSyntheticEmailWhenLineVerifyEndpointReturns400() {
        var server = lineServerUpToVerify();
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(LINE_VERIFY_URL)).andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest());
        User dave = existingLineUser("line_u123@line.oauth");

        assertThat(authService.lineLogin("code", "https://app.example.com/cb").user().id()).isEqualTo(dave.getId().toString());
        server.verify();
    }

    @Test
    void shouldUseSyntheticEmailWhenLineVerifyEndpointReturns404() {
        var server = lineServerUpToVerify();
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(LINE_VERIFY_URL)).andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound());
        User dave = existingLineUser("line_u123@line.oauth");

        assertThat(authService.lineLogin("code", "https://app.example.com/cb").user().id()).isEqualTo(dave.getId().toString());
        server.verify();
    }

    @Test
    void shouldThrow503WithoutCreatingUserWhenLineVerifyEndpointRateLimits() {
        var server = lineServerUpToVerify();
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(LINE_VERIFY_URL)).andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withTooManyRequests());

        assertApiError(org.assertj.core.api.Assertions.catchThrowable(() -> authService.lineLogin("code", "https://app.example.com/cb")),
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, MessageKey.COMMON_SERVICE_UNAVAILABLE);
        server.verify();
        verify(userRepository, never()).save(any());
    }

    @Test
    void shouldThrow401ProfileFailedWhenLineUserIdIsBlank() {
        var server = lineServer();
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(LINE_TOKEN_URL)).andRespond(lineJson("{\"access_token\":\"at\"}"));
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(LINE_PROFILE_URL)).andRespond(lineJson("{\"userId\":\"  \",\"displayName\":\"Dave\"}"));

        assertApiError(org.assertj.core.api.Assertions.catchThrowable(() -> authService.lineLogin("code", "https://app.example.com/cb")),
                org.springframework.http.HttpStatus.UNAUTHORIZED, MessageKey.AUTH_LINE_PROFILE_FAILED);
        server.verify();
        verify(userRepository, never()).save(any());
    }

    @Test
    void shouldThrow401TokenExchangeFailedWhenLineAccessTokenIsBlank() {
        var server = lineServer();
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(LINE_TOKEN_URL)).andRespond(lineJson("{\"access_token\":\"\"}"));

        assertApiError(org.assertj.core.api.Assertions.catchThrowable(() -> authService.lineLogin("code", "https://app.example.com/cb")),
                org.springframework.http.HttpStatus.UNAUTHORIZED, MessageKey.AUTH_LINE_TOKEN_EXCHANGE_FAILED);
        server.verify();
    }

}
