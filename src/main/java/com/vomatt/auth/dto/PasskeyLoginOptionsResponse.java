package com.vomatt.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * WebAuthn {@code PublicKeyCredentialRequestOptionsJSON} for a usernameless sign-in: pass it to
 * {@code PublicKeyCredential.parseRequestOptionsFromJSON} and then {@code navigator.credentials.get({ publicKey })}.
 */
@Schema(description = "PublicKeyCredentialRequestOptionsJSON for navigator.credentials.get(); binary fields are base64url without padding")
public record PasskeyLoginOptionsResponse(
        @Schema(description = "One-time challenge (base64url); expires after 5 minutes and is spent by one login/verify call",
                example = "Qm8AYkRgZ3B1c2hxS1d0X0Nmc2Vh") String challenge,
        @Schema(description = "Milliseconds the browser may wait for the user; matches the challenge lifetime", example = "300000") long timeout,
        @Schema(description = "Always the configured RP ID", example = "vomatt.com") String rpId,
        @Schema(description = "Always empty: the user picks any of their passkeys (usernameless)")
        List<PasskeyRegistrationOptionsResponse.CredentialDescriptor> allowCredentials,
        @Schema(example = "preferred") String userVerification) {
}
