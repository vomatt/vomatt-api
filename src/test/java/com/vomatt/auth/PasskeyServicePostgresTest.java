package com.vomatt.auth;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

import com.vomatt.auth.dto.PasskeyRegistrationOptionsResponse;
import com.vomatt.common.redis.RedisService;
import com.vomatt.entity.PasskeyCredential;
import com.vomatt.entity.User;
import com.vomatt.repository.PasskeyCredentialRepository;
import com.vomatt.repository.PostgresRepositoryTest;
import com.vomatt.repository.UserRepository;
import com.webauthn4j.data.AttestationConveyancePreference;
import com.webauthn4j.data.AuthenticatorSelectionCriteria;
import com.webauthn4j.data.PublicKeyCredentialCreationOptions;
import com.webauthn4j.data.PublicKeyCredentialParameters;
import com.webauthn4j.data.PublicKeyCredentialRpEntity;
import com.webauthn4j.data.PublicKeyCredentialType;
import com.webauthn4j.data.PublicKeyCredentialUserEntity;
import com.webauthn4j.data.ResidentKeyRequirement;
import com.webauthn4j.data.UserVerificationRequirement;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.client.challenge.DefaultChallenge;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** PasskeyService against real tables; Redis is a map with the same set / getAndDelete semantics. */
@DisplayName("PasskeyService (Postgres)")
class PasskeyServicePostgresTest extends PostgresRepositoryTest {

    static final List<String> PROD_ORIGINS = List.of("https://vomatt.com", "https://admin.vomatt.com");

    @Autowired PasskeyCredentialRepository passkeyCredentialRepository;
    @Autowired UserRepository userRepository;

    final RedisService redisService = mock(RedisService.class);
    final Map<String, Object> redis = new HashMap<>();
    PasskeyService passkeyService;
    User user;

    @BeforeEach
    void setUp() {
        doAnswer(inv -> redis.put(inv.getArgument(0) + ":" + inv.getArgument(1), inv.getArgument(2)))
                .when(redisService).setOrThrow(anyString(), anyString(), any(), any(Duration.class));
        when(redisService.getAndDelete(anyString(), anyString(), any()))
                .thenAnswer(inv -> redis.remove(inv.getArgument(0) + ":" + inv.getArgument(1)));
        passkeyService = service(PROD_ORIGINS);
        user = persistUser("passkey-" + UUID.randomUUID().toString().substring(0, 8));
        user.setDisplayName("Ming Wang");
        em.flush();
    }

    PasskeyService service(List<String> origins) {
        return new PasskeyService(passkeyCredentialRepository, userRepository, redisService, "vomatt.com", origins);
    }

    /** Turns the options JSON back into what the browser's parseCreationOptionsFromJSON would build. */
    static PublicKeyCredentialCreationOptions toWebAuthn(PasskeyRegistrationOptionsResponse o) {
        Base64.Decoder b64 = Base64.getUrlDecoder();
        return new PublicKeyCredentialCreationOptions(
                new PublicKeyCredentialRpEntity(o.rp().id(), o.rp().name()),
                new PublicKeyCredentialUserEntity(b64.decode(o.user().id()), o.user().name(), o.user().displayName()),
                new DefaultChallenge(b64.decode(o.challenge())),
                o.pubKeyCredParams().stream().map(p -> new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY,
                        COSEAlgorithmIdentifier.create(p.alg()))).toList(),
                o.timeout(), List.of(),
                new AuthenticatorSelectionCriteria(null, true, ResidentKeyRequirement.create(o.authenticatorSelection().residentKey()),
                        UserVerificationRequirement.create(o.authenticatorSelection().userVerification())),
                AttestationConveyancePreference.create(o.attestation()), null);
    }

    // ── register/options ─────────────────────────────────────────────

    @Test
    @DisplayName("should default the RP ID to vomatt.com in application.yml, not the API host")
    void shouldUseConfiguredRpIdWhenBuildingOptions() throws Exception {
        MutablePropertySources sources = new MutablePropertySources();
        sources.addFirst(new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml")).get(0));
        PropertySourcesPropertyResolver env = new PropertySourcesPropertyResolver(sources);
        assertThat(env.resolvePlaceholders("${passkey.rp-id}")).isEqualTo("vomatt.com");
        assertThat(env.resolvePlaceholders("${passkey.allowed-origins}")).isEqualTo(String.join(",", PROD_ORIGINS));

        assertThat(passkeyService.registrationOptions(user.getId().toString()).rp().id()).isEqualTo("vomatt.com");
    }

    @Test
    @DisplayName("should require a discoverable credential, prefer UV, skip attestation and offer only ES256 / RS256")
    void shouldReturnRequiredOptionsWhenRegistering() {
        PasskeyRegistrationOptionsResponse options = passkeyService.registrationOptions(user.getId().toString());

        assertThat(options.authenticatorSelection().residentKey()).isEqualTo("required");
        assertThat(options.authenticatorSelection().requireResidentKey()).isTrue();
        assertThat(options.authenticatorSelection().userVerification()).isEqualTo("preferred");
        assertThat(options.attestation()).isEqualTo("none");
        assertThat(options.pubKeyCredParams()).extracting(PasskeyRegistrationOptionsResponse.PubKeyCredParam::alg)
                .containsExactly(-7, -257);
        assertThat(options.pubKeyCredParams()).extracting(PasskeyRegistrationOptionsResponse.PubKeyCredParam::type)
                .containsOnly("public-key");
        assertThat(options.user().name()).isEqualTo(user.getEmail());
        assertThat(options.user().displayName()).isEqualTo("Ming Wang");
        assertThat(Base64.getUrlDecoder().decode(options.user().id())).hasSize(16);
        assertThat(options.timeout()).isEqualTo(300_000);
    }

    @Test
    @DisplayName("should put hints at the top level of the options JSON, not inside authenticatorSelection")
    void shouldPlaceHintsAtTopLevelWhenSerialized() {
        JsonNode json = JsonMapper.builder().build().valueToTree(passkeyService.registrationOptions(user.getId().toString()));

        assertThat(json.get("hints").isArray()).isTrue();
        assertThat(json.get("hints")).isNotEmpty();
        assertThat(json.get("authenticatorSelection").has("hints")).isFalse();
    }

    @Test
    @DisplayName("should list the user's existing passkeys in excludeCredentials, and nobody else's")
    void shouldExcludeExistingCredentialsWhenUserHasPasskeys() {
        assertThat(passkeyService.registrationOptions(user.getId().toString()).excludeCredentials()).isEmpty();

        PasskeyCredential saved = passkeyCredentialRepository.saveAndFlush(
                PasskeyService.toEntity(user, PasskeyEmulator.registeredRecord(), null));
        User other = persistUser("passkey-other-" + UUID.randomUUID().toString().substring(0, 8));

        assertThat(passkeyService.registrationOptions(user.getId().toString()).excludeCredentials())
                .singleElement()
                .satisfies(d -> {
                    assertThat(d.id()).isEqualTo(Base64.getUrlEncoder().withoutPadding().encodeToString(saved.getCredentialId()));
                    assertThat(d.type()).isEqualTo("public-key");
                });
        assertThat(passkeyService.registrationOptions(other.getId().toString()).excludeCredentials()).isEmpty();
    }

    @Test
    @DisplayName("should store the challenge in Redis under passkey:challenge for 5 minutes, bound to the user")
    void shouldStoreChallengeWithFiveMinuteTtlWhenIssuingOptions() {
        String challenge = passkeyService.registrationOptions(user.getId().toString()).challenge();

        verify(redisService).setOrThrow(eq("passkey:challenge"), eq(challenge),
                eq(new PasskeyService.PasskeyChallenge(PasskeyService.PasskeyChallenge.Purpose.REGISTRATION, user.getId().toString())),
                eq(Duration.ofMinutes(5)));
        assertThat(Base64.getUrlDecoder().decode(challenge)).hasSize(32);
    }
}
