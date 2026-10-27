package com.vomatt.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import com.vomatt.auth.dto.PasskeyLoginOptionsResponse;
import com.vomatt.auth.dto.PasskeyLoginVerifyRequest;
import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.redis.RedisService;
import com.vomatt.entity.PasskeyCredential;
import com.vomatt.entity.User;
import com.vomatt.repository.PasskeyCredentialRepository;
import com.vomatt.repository.PostgresRepositoryTest;
import com.vomatt.repository.UserRepository;
import com.webauthn4j.data.PublicKeyCredentialRequestOptions;
import com.webauthn4j.data.UserVerificationRequirement;
import com.webauthn4j.data.client.challenge.DefaultChallenge;

import tools.jackson.databind.json.JsonMapper;

/** Usernameless passkey sign-in against real tables; Redis is a map with the same set / getAndDelete semantics. */
@DisplayName("PasskeyService sign-in (Postgres)")
class PasskeyLoginPostgresTest extends PostgresRepositoryTest {

    @Autowired PasskeyCredentialRepository passkeyCredentialRepository;
    @Autowired UserRepository userRepository;

    final RedisService redisService = mock(RedisService.class);
    final Map<String, Object> redis = new HashMap<>();
    PasskeyService passkeyService;
    User user;
    PasskeyEmulator emulator;

    @BeforeEach
    void setUp() {
        doAnswer(inv -> redis.put(inv.getArgument(0) + ":" + inv.getArgument(1), inv.getArgument(2)))
                .when(redisService).setOrThrow(anyString(), anyString(), any(), any(Duration.class));
        when(redisService.getAndDelete(anyString(), anyString(), any()))
                .thenAnswer(inv -> redis.remove(inv.getArgument(0) + ":" + inv.getArgument(1)));
        passkeyService = new PasskeyService(passkeyCredentialRepository, userRepository, redisService, "vomatt.com",
                PasskeyServicePostgresTest.PROD_ORIGINS);
        user = persistUser("passkey-login-" + UUID.randomUUID().toString().substring(0, 8));
        em.flush();
        emulator = new PasskeyEmulator("https://vomatt.com");
    }

    /** Registers a passkey for {@link #user} on {@link #emulator} through the real register flow. */
    private PasskeyCredential register() {
        var options = PasskeyServicePostgresTest.toWebAuthn(passkeyService.registrationOptions(user.getId().toString()));
        var credential = JsonMapper.builder().build().convertValue(emulator.create(options),
                com.vomatt.auth.dto.PasskeyRegistrationVerifyRequest.Credential.class);
        var saved = passkeyService.verifyRegistration(user.getId().toString(),
                new com.vomatt.auth.dto.PasskeyRegistrationVerifyRequest(credential, null));
        return passkeyCredentialRepository.findById(saved.id()).orElseThrow();
    }

    private static PublicKeyCredentialRequestOptions toWebAuthn(PasskeyLoginOptionsResponse o) {
        return new PublicKeyCredentialRequestOptions(new DefaultChallenge(Base64.getUrlDecoder().decode(o.challenge())),
                o.timeout(), o.rpId(), List.of(), UserVerificationRequirement.create(o.userVerification()), null);
    }

    private Map<String, Object> signIn() {
        return emulator.get(toWebAuthn(passkeyService.loginOptions()));
    }

    private static PasskeyLoginVerifyRequest request(Map<String, Object> json) {
        return new PasskeyLoginVerifyRequest(JsonMapper.builder().build().convertValue(json, PasskeyLoginVerifyRequest.Credential.class));
    }

    private static void assertInvalid(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getMessageKey()).isEqualTo(MessageKey.AUTH_PASSKEY_INVALID);
            assertThat(e.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        });
    }

    @Test
    @DisplayName("should issue a usernameless challenge: empty allowCredentials, RP ID, 5-minute TTL, no user bound")
    void shouldReturnEmptyAllowCredentialsWhenIssuingLoginOptions() {
        PasskeyLoginOptionsResponse options = passkeyService.loginOptions();

        assertThat(options.allowCredentials()).isEmpty();
        assertThat(options.rpId()).isEqualTo("vomatt.com");
        assertThat(options.userVerification()).isEqualTo("preferred");
        verify(redisService).setOrThrow(eq("passkey:challenge"), eq(options.challenge()),
                eq(new PasskeyService.PasskeyChallenge(PasskeyService.PasskeyChallenge.Purpose.AUTHENTICATION, null)),
                eq(Duration.ofMinutes(5)));
    }

    @Test
    @DisplayName("should sign the passkey's owner in and record the new sign count and last use")
    void shouldReturnOwnerAndUpdateCounterWhenSignInVerifies() {
        PasskeyCredential registered = register();
        long countAtRegistration = registered.getSignCount();

        User signedIn = passkeyService.authenticate(request(signIn()));
        em.flush();
        em.clear();

        assertThat(signedIn.getId()).isEqualTo(user.getId());
        PasskeyCredential after = passkeyCredentialRepository.findById(registered.getId()).orElseThrow();
        assertThat(after.getSignCount()).isGreaterThan(countAtRegistration);
        assertThat(after.getLastUsedAt()).isNotNull();
        assertThat(redis).isEmpty();
    }

    @Test
    @DisplayName("should accept an authenticator that always reports sign count 0 (synced passkeys)")
    void shouldAcceptWhenSignCountIsZero() {
        emulator.withoutCounter();
        PasskeyCredential registered = register();

        passkeyService.authenticate(request(signIn()));
        assertThat(passkeyService.authenticate(request(signIn())).getId()).isEqualTo(user.getId());
        em.flush();
        em.clear();

        assertThat(passkeyCredentialRepository.findById(registered.getId()).orElseThrow().getSignCount()).isZero();
    }

    @Test
    @DisplayName("should record a sign count that went backwards rather than reject the sign-in")
    void shouldRecordWhenSignCountGoesBackwards() {
        PasskeyCredential registered = register();
        registered.setSignCount(1_000);
        passkeyCredentialRepository.saveAndFlush(registered);

        assertThat(passkeyService.authenticate(request(signIn())).getId()).isEqualTo(user.getId());
        em.flush();
        em.clear();

        assertThat(passkeyCredentialRepository.findById(registered.getId()).orElseThrow().getSignCount()).isLessThan(1_000);
    }

    @Test
    @DisplayName("should reject a passkey the server does not know")
    void shouldRejectWhenCredentialUnknown() {
        register();
        Map<String, Object> json = signIn();
        passkeyCredentialRepository.deleteAll();
        passkeyCredentialRepository.flush();

        assertInvalid(() -> passkeyService.authenticate(request(json)));
    }

    @Test
    @DisplayName("should reject a wrong signature")
    void shouldRejectWhenSignatureWrong() {
        register();
        Map<String, Object> json = signIn();
        @SuppressWarnings("unchecked")
        Map<String, Object> response = new LinkedHashMap<>((Map<String, Object>) json.get("response"));
        byte[] signature = Base64.getUrlDecoder().decode((String) response.get("signature"));
        signature[signature.length - 1] ^= 1;
        response.put("signature", Base64.getUrlEncoder().withoutPadding().encodeToString(signature));
        json.put("response", response);

        assertInvalid(() -> passkeyService.authenticate(request(json)));
    }

    @Test
    @DisplayName("should reject a sign-in from another origin (https://vomatt.co)")
    void shouldRejectWhenOriginWrong() {
        register();

        assertInvalid(() -> passkeyService.authenticate(request(emulator.withOrigin("https://vomatt.co").get(
                toWebAuthn(passkeyService.loginOptions())))));
    }

    @Test
    @DisplayName("should reject the same sign-in a second time: the challenge was spent by getAndDelete")
    void shouldRejectWhenChallengeReplayed() {
        register();
        PasskeyLoginVerifyRequest request = request(signIn());
        passkeyService.authenticate(request);

        assertInvalid(() -> passkeyService.authenticate(request));
    }

    @Test
    @DisplayName("should reject a registration challenge used to sign in, and an expired one")
    void shouldRejectWhenChallengeHasWrongPurposeOrExpired() {
        register();
        String registrationChallenge = passkeyService.registrationOptions(user.getId().toString()).challenge();
        PasskeyLoginOptionsResponse forged = new PasskeyLoginOptionsResponse(registrationChallenge, 300_000, "vomatt.com",
                List.of(), "preferred");
        assertInvalid(() -> passkeyService.authenticate(request(emulator.get(toWebAuthn(forged)))));

        Map<String, Object> json = signIn();
        redis.clear(); // the 5-minute TTL elapsed
        assertInvalid(() -> passkeyService.authenticate(request(json)));
    }

    @Test
    @DisplayName("should answer auth.passkey.invalid, not 500, for a malformed response")
    void shouldRejectWhenResponseMalformed() {
        assertInvalid(() -> passkeyService.authenticate(new PasskeyLoginVerifyRequest(new PasskeyLoginVerifyRequest.Credential(
                "a", "a", "public-key", new PasskeyLoginVerifyRequest.Response("%%%", "%%%", "%%%", null), null, null))));
    }
}
