package com.vomatt.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

@Schema(description = "A new passkey: the browser's `credential.toJSON()` and an optional name")
public record PasskeyRegistrationVerifyRequest(
        @Schema(description = "`credential.toJSON()` of the PublicKeyCredential returned by navigator.credentials.create()")
        @NotNull @Valid Credential credential,
        @Schema(description = "Name shown in the passkey list; null keeps it unnamed", example = "iPhone", nullable = true)
        @Size(max = 100) String name) {

    /** WebAuthn {@code RegistrationResponseJSON}; fields this API does not read (publicKey, authenticatorData...) are ignored. */
    @Schema(name = "PasskeyRegistrationCredential", description = "RegistrationResponseJSON; binary fields are base64url")
    public record Credential(
            @Schema(example = "RBBLHcqGtSGftlCq_9zzjSpKZIdZvgpor7plCJ4BJbI") @NotBlank String id,
            @Schema(example = "RBBLHcqGtSGftlCq_9zzjSpKZIdZvgpor7plCJ4BJbI") @NotBlank String rawId,
            @Schema(example = "public-key") @NotBlank String type,
            @NotNull @Valid Response response,
            @Schema(example = "platform", nullable = true) String authenticatorAttachment,
            @Schema(description = "Client extension outputs; `{}` when none", nullable = true) Map<String, Object> clientExtensionResults) {
    }

    @Schema(name = "PasskeyAttestationResponse", description = "AuthenticatorAttestationResponseJSON")
    public record Response(
            @Schema(example = "eyJ0eXBlIjoid2ViYXV0aG4uY3JlYXRlIiwiY2hhbGxlbmdlIjoiUW04QSJ9") @NotBlank String clientDataJSON,
            @Schema(example = "o2NmbXRkbm9uZWdhdHRTdG10oGhhdXRoRGF0YVjF") @NotBlank String attestationObject,
            @Schema(example = "[\"internal\", \"hybrid\"]", nullable = true) List<String> transports) {
    }
}
