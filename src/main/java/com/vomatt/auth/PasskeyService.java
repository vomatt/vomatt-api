package com.vomatt.auth;

import com.vomatt.auth.dto.PasskeyLoginOptionsResponse;
import com.vomatt.auth.dto.PasskeyLoginVerifyRequest;
import com.vomatt.auth.dto.PasskeyRegistrationOptionsResponse;
import com.vomatt.auth.dto.PasskeyRegistrationVerifyRequest;
import com.vomatt.auth.dto.PasskeyResponse;
import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.redis.RedisService;
import com.vomatt.entity.PasskeyCredential;
import com.vomatt.entity.User;
import com.vomatt.repository.PasskeyCredentialRepository;
import com.vomatt.repository.UserRepository;
import com.webauthn4j.WebAuthnManager;
import com.webauthn4j.converter.AttestedCredentialDataConverter;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.credential.CredentialRecord;
import com.webauthn4j.credential.CredentialRecordImpl;
import com.webauthn4j.data.AuthenticationData;
import com.webauthn4j.data.AuthenticationParameters;
import com.webauthn4j.data.AuthenticatorTransport;
import com.webauthn4j.data.PublicKeyCredentialParameters;
import com.webauthn4j.data.PublicKeyCredentialType;
import com.webauthn4j.data.RegistrationData;
import com.webauthn4j.data.RegistrationParameters;
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.Challenge;
import com.webauthn4j.server.ServerProperty;
import com.webauthn4j.util.exception.WebAuthnException;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Passkeys (WebAuthn). All webauthn4j code lives here, so a webauthn4j upgrade touches only this class. */
@Slf4j
@Service
public class PasskeyService {

    // webauthn4j's own converter: its CBOR / COSE modules are not registered on the app's JSON mapper
    private static final ObjectConverter OBJECT_CONVERTER = new ObjectConverter();
    private static final AttestedCredentialDataConverter ATTESTED_CREDENTIAL_DATA_CONVERTER =
            new AttestedCredentialDataConverter(OBJECT_CONVERTER);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

    /** Issued challenges, keyed by their base64url value; each is spent by one verify (getAndDelete). */
    static final String CHALLENGE_NS = "passkey:challenge";
    static final Duration CHALLENGE_TTL = Duration.ofMinutes(5);

    private static final WebAuthnManager WEB_AUTHN_MANAGER = WebAuthnManager.createNonStrictWebAuthnManager(OBJECT_CONVERTER);

    static {
        // A sign count that did not increase may mean a cloned authenticator, but synced passkeys report 0 or reuse
        // counts, so it is only logged; the new count is still stored
        WEB_AUTHN_MANAGER.getAuthenticationDataVerifier().setMaliciousCounterValueHandler(
                object -> log.warn("Passkey sign count did not increase (recorded, not rejected)"));
    }

    private static final String RP_NAME = "Vomatt";
    private static final String PUBLIC_KEY = "public-key";
    /** Offered in the options and enforced at verify: ES256 (-7), then RS256 (-257). */
    private static final List<PublicKeyCredentialParameters> PUB_KEY_CRED_PARAMS = List.of(
            new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.ES256),
            new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.RS256));
    // Platform authenticators first, phones next, security keys still offered (admins may use one)
    private static final List<String> HINTS = List.of("client-device", "hybrid", "security-key");

    /** What an issued challenge is for and whom; verify rejects a challenge issued for another purpose or user. */
    record PasskeyChallenge(Purpose purpose, String userId) {
        enum Purpose { REGISTRATION, AUTHENTICATION }
    }

    private final PasskeyCredentialRepository passkeyCredentialRepository;
    private final UserRepository userRepository;
    private final RedisService redisService;
    private final String rpId;
    private final Set<Origin> allowedOrigins;

    public PasskeyService(PasskeyCredentialRepository passkeyCredentialRepository, UserRepository userRepository,
                          RedisService redisService, @Value("${passkey.rp-id}") String rpId,
                          @Value("${passkey.allowed-origins}") List<String> allowedOrigins) {
        this.passkeyCredentialRepository = passkeyCredentialRepository;
        this.userRepository = userRepository;
        this.redisService = redisService;
        this.rpId = rpId;
        // Parsed at startup, so a malformed PASSKEY_ALLOWED_ORIGINS fails the boot instead of every verify
        this.allowedOrigins = allowedOrigins.stream().map(String::strip).map(Origin::new).collect(Collectors.toUnmodifiableSet());
    }

    @Transactional(readOnly = true)
    public PasskeyRegistrationOptionsResponse registrationOptions(String userId) {
        User user = userRepository.findById(UUID.fromString(userId))
                .orElseThrow(() -> ApiException.notFound(MessageKey.USER_NOT_FOUND));
        String challenge = newChallenge(new PasskeyChallenge(PasskeyChallenge.Purpose.REGISTRATION, userId));
        List<PasskeyRegistrationOptionsResponse.CredentialDescriptor> exclude = passkeyCredentialRepository
                .findByUserIdOrderByCreatedAt(user.getId()).stream()
                .map(c -> new PasskeyRegistrationOptionsResponse.CredentialDescriptor(PUBLIC_KEY,
                        BASE64URL.encodeToString(c.getCredentialId()), List.of(c.getTransports())))
                .toList();
        String name = user.getEmail() != null ? user.getEmail() : user.getUsername();
        String displayName = user.getDisplayName() != null && !user.getDisplayName().isBlank()
                ? user.getDisplayName() : user.getUsername();
        return new PasskeyRegistrationOptionsResponse(
                new PasskeyRegistrationOptionsResponse.Rp(rpId, RP_NAME),
                new PasskeyRegistrationOptionsResponse.User(BASE64URL.encodeToString(uuidBytes(user.getId())), name, displayName),
                challenge,
                PUB_KEY_CRED_PARAMS.stream()
                        .map(p -> new PasskeyRegistrationOptionsResponse.PubKeyCredParam(PUBLIC_KEY, (int) p.getAlg().getValue()))
                        .toList(),
                CHALLENGE_TTL.toMillis(),
                exclude,
                new PasskeyRegistrationOptionsResponse.AuthenticatorSelection("required", true, "preferred"),
                HINTS,
                "none");
    }

    /**
     * Verifies {@code credential.toJSON()} against a challenge this user was issued and stores the passkey. Any
     * malformed, forged, replayed or expired response is 400 {@code auth.passkey.invalid}.
     */
    @Transactional
    public PasskeyResponse verifyRegistration(String userId, PasskeyRegistrationVerifyRequest request) {
        RegistrationData data;
        try {
            data = WEB_AUTHN_MANAGER.parseRegistrationResponseJSON(OBJECT_CONVERTER.getJsonConverter().writeValueAsString(
                    request.credential().clientExtensionResults() == null
                            ? withEmptyExtensions(request.credential()) : request.credential()));
        } catch (WebAuthnException | JacksonException | IllegalArgumentException e) {
            // webauthn4j lets some decoding errors through raw (bad base64, truncated CBOR / JSON)
            throw invalid("unparseable registration response", e);
        }
        if (data.getCollectedClientData() == null) {
            throw invalid("registration response without client data", null);
        }
        // The key is the challenge itself, so webauthn4j's own challenge check is a tautology: the security is that
        // the challenge was issued (present), is spent here (getAndDelete) and was issued to this user for registration
        Challenge challenge = data.getCollectedClientData().getChallenge();
        PasskeyChallenge issued = challenge == null ? null
                : redisService.getAndDelete(CHALLENGE_NS, BASE64URL.encodeToString(challenge.getValue()), PasskeyChallenge.class);
        if (issued == null || issued.purpose() != PasskeyChallenge.Purpose.REGISTRATION || !userId.equals(issued.userId())) {
            throw invalid("unknown, spent, expired or foreign challenge", null);
        }
        ServerProperty serverProperty = ServerProperty.builder()
                .origins(allowedOrigins)
                .rpId(rpId)
                .challenge(challenge)
                .build();
        try {
            WEB_AUTHN_MANAGER.verify(data, new RegistrationParameters(serverProperty, PUB_KEY_CRED_PARAMS, false, true));
        } catch (WebAuthnException e) {
            throw invalid("registration response failed verification", e);
        }
        User user = userRepository.findById(UUID.fromString(userId))
                .orElseThrow(() -> ApiException.notFound(MessageKey.USER_NOT_FOUND));
        CredentialRecord record = new CredentialRecordImpl(data.getAttestationObject(), data.getCollectedClientData(),
                data.getClientExtensions(), data.getTransports());
        String name = request.name() == null || request.name().isBlank() ? null : request.name().strip();
        PasskeyCredential saved = passkeyCredentialRepository.saveAndFlush(toEntity(user, record, name));
        return new PasskeyResponse(saved.getId(), saved.getName(), saved.getCreatedAt(), saved.getLastUsedAt());
    }

    /** Usernameless sign-in options: no allowCredentials, so the user picks any passkey for this RP. */
    public PasskeyLoginOptionsResponse loginOptions() {
        String challenge = newChallenge(new PasskeyChallenge(PasskeyChallenge.Purpose.AUTHENTICATION, null));
        return new PasskeyLoginOptionsResponse(challenge, CHALLENGE_TTL.toMillis(), rpId, List.of(), "preferred");
    }

    /**
     * Verifies {@code credential.toJSON()} from a sign-in and returns the passkey's owner, after recording the new sign
     * count and last use. Any malformed, forged, replayed or expired response, or an unknown passkey, is 401
     * {@code auth.passkey.invalid}; the caller issues the tokens.
     */
    @Transactional
    public User authenticate(PasskeyLoginVerifyRequest request) {
        AuthenticationData data;
        try {
            PasskeyLoginVerifyRequest.Credential c = request.credential();
            data = WEB_AUTHN_MANAGER.parseAuthenticationResponseJSON(OBJECT_CONVERTER.getJsonConverter().writeValueAsString(
                    c.clientExtensionResults() != null ? c : new PasskeyLoginVerifyRequest.Credential(c.id(), c.rawId(),
                            c.type(), c.response(), c.authenticatorAttachment(), Map.of())));
        } catch (WebAuthnException | JacksonException | IllegalArgumentException e) {
            throw loginRejected("unparseable authentication response", e);
        }
        Challenge challenge = data.getCollectedClientData() == null ? null : data.getCollectedClientData().getChallenge();
        PasskeyChallenge issued = challenge == null ? null
                : redisService.getAndDelete(CHALLENGE_NS, BASE64URL.encodeToString(challenge.getValue()), PasskeyChallenge.class);
        if (issued == null || issued.purpose() != PasskeyChallenge.Purpose.AUTHENTICATION) {
            throw loginRejected("unknown, spent, expired or registration challenge", null);
        }
        PasskeyCredential credential = passkeyCredentialRepository.findByCredentialId(data.getCredentialId())
                .orElseThrow(() -> loginRejected("unknown credential", null));
        // The authenticator names the account the passkey was created for; it must be the passkey's owner
        if (data.getUserHandle() != null && !Arrays.equals(data.getUserHandle(), uuidBytes(credential.getUser().getId()))) {
            throw loginRejected("user handle does not match the credential's owner", null);
        }
        CredentialRecord record = toRecord(credential);
        ServerProperty serverProperty = ServerProperty.builder().origins(allowedOrigins).rpId(rpId).challenge(challenge).build();
        try {
            WEB_AUTHN_MANAGER.verify(data, new AuthenticationParameters(serverProperty, record, null, false, true));
        } catch (WebAuthnException e) {
            throw loginRejected("authentication response failed verification", e);
        }
        // verify() updated the record from the authenticator data
        credential.setSignCount(record.getCounter());
        credential.setBackedUp(Boolean.TRUE.equals(record.isBackedUp()));
        credential.setUvInitialized(Boolean.TRUE.equals(record.isUvInitialized()));
        credential.setLastUsedAt(OffsetDateTime.now());
        passkeyCredentialRepository.save(credential);
        return credential.getUser();
    }

    private static ApiException loginRejected(String reason, Exception cause) {
        log.info("Passkey sign-in rejected: {}{}", reason, cause == null ? "" : " (" + cause.getMessage() + ")");
        return ApiException.unauthorized(MessageKey.AUTH_PASSKEY_INVALID);
    }

    private static PasskeyRegistrationVerifyRequest.Credential withEmptyExtensions(PasskeyRegistrationVerifyRequest.Credential c) {
        return new PasskeyRegistrationVerifyRequest.Credential(c.id(), c.rawId(), c.type(), c.response(),
                c.authenticatorAttachment(), Map.of());
    }

    private static ApiException invalid(String reason, Exception cause) {
        log.info("Passkey rejected: {}{}", reason, cause == null ? "" : " (" + cause.getMessage() + ")");
        return ApiException.badRequest(MessageKey.AUTH_PASSKEY_INVALID);
    }

    private String newChallenge(PasskeyChallenge purpose) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String challenge = BASE64URL.encodeToString(bytes);
        redisService.setOrThrow(CHALLENGE_NS, challenge, purpose, CHALLENGE_TTL);
        return challenge;
    }

    private static byte[] uuidBytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }

    static PasskeyCredential toEntity(User user, CredentialRecord record, String name) {
        AttestedCredentialData data = record.getAttestedCredentialData();
        Set<AuthenticatorTransport> transports = record.getTransports() == null ? Set.of() : record.getTransports();
        return PasskeyCredential.builder()
                .user(user)
                .credentialId(data.getCredentialId())
                .attestedCredentialData(ATTESTED_CREDENTIAL_DATA_CONVERTER.convert(data))
                .signCount(record.getCounter())
                .transports(transports.stream().map(AuthenticatorTransport::getValue).sorted().toArray(String[]::new))
                .uvInitialized(Boolean.TRUE.equals(record.isUvInitialized()))
                .backupEligible(Boolean.TRUE.equals(record.isBackupEligible()))
                .backedUp(Boolean.TRUE.equals(record.isBackedUp()))
                .name(name)
                .build();
    }

    static CredentialRecord toRecord(PasskeyCredential credential) {
        // Attestation is "none" and client data / extensions are not needed after registration, so they are not kept
        return new CredentialRecordImpl(null, credential.isUvInitialized(), credential.isBackupEligible(),
                credential.isBackedUp(), credential.getSignCount(),
                ATTESTED_CREDENTIAL_DATA_CONVERTER.convert(credential.getAttestedCredentialData()), null, null, null,
                Arrays.stream(credential.getTransports()).map(AuthenticatorTransport::create).collect(Collectors.toSet()));
    }
}
