package com.vomatt.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

@Schema(description = "A passkey sign-in: the browser's `credential.toJSON()`")
public record PasskeyLoginVerifyRequest(
        @Schema(description = "`credential.toJSON()` of the PublicKeyCredential returned by navigator.credentials.get()")
        @NotNull @Valid Credential credential) {

    /** WebAuthn {@code AuthenticationResponseJSON}. */
    @Schema(name = "PasskeyAuthenticationCredential", description = "AuthenticationResponseJSON; binary fields are base64url")
    public record Credential(
            @Schema(example = "RBBLHcqGtSGftlCq_9zzjSpKZIdZvgpor7plCJ4BJbI") @NotBlank String id,
            @Schema(example = "RBBLHcqGtSGftlCq_9zzjSpKZIdZvgpor7plCJ4BJbI") @NotBlank String rawId,
            @Schema(example = "public-key") @NotBlank String type,
            @NotNull @Valid Response response,
            @Schema(example = "platform", nullable = true) String authenticatorAttachment,
            @Schema(description = "Client extension outputs; `{}` when none", nullable = true) Map<String, Object> clientExtensionResults) {
    }

    @Schema(name = "PasskeyAssertionResponse", description = "AuthenticatorAssertionResponseJSON")
    public record Response(
            @Schema(example = "eyJ0eXBlIjoid2ViYXV0aG4uZ2V0IiwiY2hhbGxlbmdlIjoiUW04QSJ9") @NotBlank String clientDataJSON,
            @Schema(example = "SZYN5YgOjGh0NBcPZHZgW4_krrmihjLHmVzzuoMdl2MFAAAAAQ") @NotBlank String authenticatorData,
            @Schema(example = "MEUCIQDq4f0Q3E4lhVb3hYQm9TLu") @NotBlank String signature,
            @Schema(description = "The user handle stored with the passkey (the user's UUID bytes)", example = "AZCosh_lJBh4kKvN7wEjRWeJ",
                    nullable = true) String userHandle) {
    }
}
