package com.vomatt.auth;

import com.webauthn4j.WebAuthnManager;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.credential.CredentialRecord;
import com.webauthn4j.credential.CredentialRecordImpl;
import com.webauthn4j.data.AttestationConveyancePreference;
import com.webauthn4j.data.AuthenticatorAssertionResponse;
import com.webauthn4j.data.AuthenticatorAttestationResponse;
import com.webauthn4j.data.AuthenticatorSelectionCriteria;
import com.webauthn4j.data.AuthenticatorTransport;
import com.webauthn4j.data.PublicKeyCredential;
import com.webauthn4j.data.PublicKeyCredentialCreationOptions;
import com.webauthn4j.data.PublicKeyCredentialParameters;
import com.webauthn4j.data.PublicKeyCredentialRequestOptions;
import com.webauthn4j.data.PublicKeyCredentialRpEntity;
import com.webauthn4j.data.PublicKeyCredentialType;
import com.webauthn4j.data.PublicKeyCredentialUserEntity;
import com.webauthn4j.data.RegistrationData;
import com.webauthn4j.data.ResidentKeyRequirement;
import com.webauthn4j.data.UserVerificationRequirement;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.extension.client.AuthenticationExtensionClientOutput;
import com.webauthn4j.data.extension.client.RegistrationExtensionClientOutput;
import com.webauthn4j.test.authenticator.webauthn.NoneAttestationAuthenticator;
import com.webauthn4j.test.authenticator.webauthn.WebAuthnAuthenticatorAdaptor;
import com.webauthn4j.test.client.ClientPlatform;
import tools.jackson.databind.json.JsonMapper;

import java.util.Base64;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** A browser with a fresh "none" attestation authenticator, answering as {@code credential.toJSON()} would. */
final class PasskeyEmulator {

    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

    private final ClientPlatform platform;

    private final NoneAttestationAuthenticator authenticator = new NoneAttestationAuthenticator();

    PasskeyEmulator(String origin) {
        platform = new ClientPlatform(new Origin(origin), new WebAuthnAuthenticatorAdaptor(authenticator));
    }

    /** Signs in from another page, with the same authenticator (and so the same passkeys). */
    PasskeyEmulator withOrigin(String origin) {
        platform.setOrigin(new Origin(origin));
        return this;
    }

    /** Synced passkeys (iCloud Keychain, Google Password Manager) always report sign count 0. */
    PasskeyEmulator withoutCounter() {
        authenticator.setCountUpEnabled(false);
        return this;
    }

    /** {@code navigator.credentials.get()} followed by {@code credential.toJSON()}. */
    Map<String, Object> get(PublicKeyCredentialRequestOptions options) {
        PublicKeyCredential<AuthenticatorAssertionResponse, AuthenticationExtensionClientOutput> credential = platform.get(options);
        AuthenticatorAssertionResponse response = credential.getResponse();
        Map<String, Object> responseJson = new LinkedHashMap<>();
        responseJson.put("clientDataJSON", B64URL.encodeToString(response.getClientDataJSON()));
        responseJson.put("authenticatorData", B64URL.encodeToString(response.getAuthenticatorData()));
        responseJson.put("signature", B64URL.encodeToString(response.getSignature()));
        responseJson.put("userHandle", response.getUserHandle() == null ? null : B64URL.encodeToString(response.getUserHandle()));
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", B64URL.encodeToString(credential.getRawId()));
        json.put("rawId", B64URL.encodeToString(credential.getRawId()));
        json.put("type", "public-key");
        json.put("response", responseJson);
        json.put("clientExtensionResults", Map.of());
        return json;
    }

    /** {@code navigator.credentials.create()} followed by {@code credential.toJSON()}. */
    Map<String, Object> create(PublicKeyCredentialCreationOptions options) {
        PublicKeyCredential<AuthenticatorAttestationResponse, RegistrationExtensionClientOutput> credential = platform.create(options);
        AuthenticatorAttestationResponse response = credential.getResponse();
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", B64URL.encodeToString(credential.getRawId()));
        json.put("rawId", B64URL.encodeToString(credential.getRawId()));
        json.put("type", "public-key");
        json.put("response", Map.of(
                "clientDataJSON", B64URL.encodeToString(response.getClientDataJSON()),
                "attestationObject", B64URL.encodeToString(response.getAttestationObject()),
                "transports", response.getTransports().stream().map(AuthenticatorTransport::getValue).toList()));
        json.put("clientExtensionResults", Map.of());
        return json;
    }

    /** A credential from the emulator, parsed the way registration parses it (signature checks are 04's concern). */
    static CredentialRecord registeredRecord() {
        PublicKeyCredentialCreationOptions options = new PublicKeyCredentialCreationOptions(
                new PublicKeyCredentialRpEntity("vomatt.com", "Vomatt"),
                new PublicKeyCredentialUserEntity(UUID.randomUUID().toString().getBytes(), "user", "User"),
                new DefaultChallenge(),
                List.of(new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.ES256)),
                null, List.of(), new AuthenticatorSelectionCriteria(null, ResidentKeyRequirement.REQUIRED,
                        UserVerificationRequirement.PREFERRED), AttestationConveyancePreference.NONE, null);
        String json = PasskeyEmulator.toJson(new PasskeyEmulator("https://vomatt.com").create(options));
        RegistrationData data = WebAuthnManager.createNonStrictWebAuthnManager(new ObjectConverter()).parseRegistrationResponseJSON(json);
        return new CredentialRecordImpl(data.getAttestationObject(), data.getCollectedClientData(), data.getClientExtensions(),
                data.getTransports());
    }

    static String toJson(Map<String, Object> value) {
        return JsonMapper.builder().build().writeValueAsString(value);
    }
}
