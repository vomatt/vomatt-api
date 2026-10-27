package com.vomatt.auth;

import com.webauthn4j.data.AuthenticatorAttestationResponse;
import com.webauthn4j.data.AuthenticatorTransport;
import com.webauthn4j.data.PublicKeyCredential;
import com.webauthn4j.data.PublicKeyCredentialCreationOptions;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.extension.client.RegistrationExtensionClientOutput;
import com.webauthn4j.test.authenticator.webauthn.NoneAttestationAuthenticator;
import com.webauthn4j.test.authenticator.webauthn.WebAuthnAuthenticatorAdaptor;
import com.webauthn4j.test.client.ClientPlatform;
import tools.jackson.databind.json.JsonMapper;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** A browser with a fresh "none" attestation authenticator, answering as {@code credential.toJSON()} would. */
final class PasskeyEmulator {

    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

    private final ClientPlatform platform;

    PasskeyEmulator(String origin) {
        platform = new ClientPlatform(new Origin(origin), new WebAuthnAuthenticatorAdaptor(new NoneAttestationAuthenticator()));
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

    static String toJson(Map<String, Object> value) {
        return JsonMapper.builder().build().writeValueAsString(value);
    }
}
