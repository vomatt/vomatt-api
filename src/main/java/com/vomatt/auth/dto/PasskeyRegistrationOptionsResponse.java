package com.vomatt.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * WebAuthn {@code PublicKeyCredentialCreationOptionsJSON}: pass it to {@code PublicKeyCredential.parseCreationOptionsFromJSON}
 * and then {@code navigator.credentials.create({ publicKey })}. Binary fields are base64url without padding.
 */
@Schema(description = "PublicKeyCredentialCreationOptionsJSON for navigator.credentials.create(); binary fields are base64url without padding")
public record PasskeyRegistrationOptionsResponse(
        @Schema(description = "Relying party; `id` is always the configured RP ID (vomatt.com), never the API host") Rp rp,
        @Schema(description = "The signed-in user the passkey is created for") User user,
        @Schema(description = "One-time challenge (base64url); expires after 5 minutes and is spent by one register/verify call",
                example = "Qm8AYkRgZ3B1c2hxS1d0X0Nmc2Vh") String challenge,
        @Schema(description = "Accepted key algorithms: ES256 (-7), then RS256 (-257)") List<PubKeyCredParam> pubKeyCredParams,
        @Schema(description = "Milliseconds the browser may wait for the user; matches the challenge lifetime", example = "300000") long timeout,
        @Schema(description = "Passkeys the user already has, so the same authenticator is not registered twice; empty when none")
        List<CredentialDescriptor> excludeCredentials,
        @Schema(description = "Discoverable credential required (usernameless sign-in); user verification preferred")
        AuthenticatorSelection authenticatorSelection,
        @Schema(description = "WebAuthn Level 3 hints, in order of preference", example = "[\"client-device\", \"hybrid\", \"security-key\"]")
        List<String> hints,
        @Schema(description = "Attestation conveyance; always `none`", example = "none") String attestation) {

    @Schema(name = "PasskeyRpEntity")
    public record Rp(@Schema(example = "vomatt.com") String id, @Schema(example = "Vomatt") String name) {
    }

    @Schema(name = "PasskeyUserEntity")
    public record User(
            @Schema(description = "User handle: the user's UUID bytes, base64url", example = "AZCosh_lJBh4kKvN7wEjRWeJ") String id,
            @Schema(description = "Account name shown in the passkey picker: the email, or the username when there is none",
                    example = "user@example.com") String name,
            @Schema(description = "Display name, or the username when there is none", example = "Ming Wang") String displayName) {
    }

    @Schema(name = "PasskeyCredentialParameters")
    public record PubKeyCredParam(@Schema(example = "public-key") String type, @Schema(example = "-7") int alg) {
    }

    @Schema(name = "PasskeyCredentialDescriptor")
    public record CredentialDescriptor(
            @Schema(example = "public-key") String type,
            @Schema(description = "Credential ID, base64url", example = "RBBLHcqGtSGftlCq_9zzjSpKZIdZvgpor7plCJ4BJbI") String id,
            @Schema(description = "Transports reported when it was registered", example = "[\"internal\", \"hybrid\"]") List<String> transports) {
    }

    @Schema(name = "PasskeyAuthenticatorSelection")
    public record AuthenticatorSelection(
            @Schema(example = "required") String residentKey,
            @Schema(description = "Level 1 spelling of residentKey=required, for older browsers", example = "true") boolean requireResidentKey,
            @Schema(example = "preferred") String userVerification) {
    }
}
