package com.vomatt.auth.dto;

import com.vomatt.entity.PasskeyCredential;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Schema(description = "A passkey the user registered")
public record PasskeyResponse(
        @Schema(example = "0199c3a2-7b5e-7c1d-9a4f-3e2b1d5c6f70") UUID id,
        @Schema(example = "iPhone", nullable = true) String name,
        OffsetDateTime createdAt,
        @Schema(description = "Last sign-in with this passkey; null when never used", nullable = true) OffsetDateTime lastUsedAt,
        @Schema(description = "How the browser can reach the authenticator (internal, hybrid, usb, nfc, ble...); empty when not reported",
                example = "[\"hybrid\", \"internal\"]") List<String> transports) {

    public static PasskeyResponse from(PasskeyCredential c) {
        return new PasskeyResponse(c.getId(), c.getName(), c.getCreatedAt(), c.getLastUsedAt(), List.of(c.getTransports()));
    }
}
