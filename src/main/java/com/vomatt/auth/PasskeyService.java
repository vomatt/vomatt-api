package com.vomatt.auth;

import com.vomatt.auth.dto.PasskeyRegistrationOptionsResponse;
import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.redis.RedisService;
import com.vomatt.entity.PasskeyCredential;
import com.vomatt.entity.User;
import com.vomatt.repository.PasskeyCredentialRepository;
import com.vomatt.repository.UserRepository;
import com.webauthn4j.converter.AttestedCredentialDataConverter;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.credential.CredentialRecord;
import com.webauthn4j.credential.CredentialRecordImpl;
import com.webauthn4j.data.AuthenticatorTransport;
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Passkeys (WebAuthn). All webauthn4j code lives here, so a webauthn4j upgrade touches only this class. */
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

    private static final String RP_NAME = "Vomatt";
    private static final String PUBLIC_KEY = "public-key";
    private static final int ES256 = -7;
    private static final int RS256 = -257;
    // Platform authenticators first, phones next, security keys still offered (admins may use one)
    private static final List<String> HINTS = List.of("client-device", "hybrid", "security-key");

    /** What an issued challenge is for and whom; verify rejects a challenge issued for another purpose or user. */
    record PasskeyChallenge(Purpose purpose, String userId) {
        enum Purpose { REGISTRATION }
    }

    private final PasskeyCredentialRepository passkeyCredentialRepository;
    private final UserRepository userRepository;
    private final RedisService redisService;
    private final String rpId;
    private final Set<String> allowedOrigins;

    public PasskeyService(PasskeyCredentialRepository passkeyCredentialRepository, UserRepository userRepository,
                          RedisService redisService, @Value("${passkey.rp-id}") String rpId,
                          @Value("${passkey.allowed-origins}") List<String> allowedOrigins) {
        this.passkeyCredentialRepository = passkeyCredentialRepository;
        this.userRepository = userRepository;
        this.redisService = redisService;
        this.rpId = rpId;
        this.allowedOrigins = Set.copyOf(allowedOrigins);
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
                List.of(new PasskeyRegistrationOptionsResponse.PubKeyCredParam(PUBLIC_KEY, ES256),
                        new PasskeyRegistrationOptionsResponse.PubKeyCredParam(PUBLIC_KEY, RS256)),
                CHALLENGE_TTL.toMillis(),
                exclude,
                new PasskeyRegistrationOptionsResponse.AuthenticatorSelection("required", true, "preferred"),
                HINTS,
                "none");
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
