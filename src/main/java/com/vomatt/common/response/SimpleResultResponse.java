package com.vomatt.common.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "Generic action result, used when an operation has no specific payload or only returns an id/status")
public record SimpleResultResponse(
        @Schema(description = "Whether the operation succeeded", example = "true") boolean success,
        @Schema(description = "ID of the affected resource; null when the operation does not target a single resource", example = "0199c3a2-7b5e-7c1d-9a4f-3e2b1d5c6f70", nullable = true) UUID id,
        @Schema(description = "New status of the affected resource; null when not applicable", example = "approved", nullable = true) String status,
        @Schema(description = "Additional message; null when none", example = "Done", nullable = true) String message
) {

    public static SimpleResultResponse ok() {
        return new SimpleResultResponse(true, null, null, null);
    }

    public static SimpleResultResponse ok(UUID id) {
        return new SimpleResultResponse(true, id, null, null);
    }

    public static SimpleResultResponse ok(UUID id, String status) {
        return new SimpleResultResponse(true, id, status, null);
    }

    public static SimpleResultResponse ok(String message) {
        return new SimpleResultResponse(true, null, null, message);
    }

    public static SimpleResultResponse of(UUID id, String status, String message) {
        return new SimpleResultResponse(true, id, status, message);
    }
}
