package com.vomatt.admin.dto;

import java.time.OffsetDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "One audit log entry: a write action and whether it succeeded")
public record AuditLogResponse(
        @Schema(description = "Entry ID (UUIDv7)", example = "0199f4c0-1a2b-7c3d-8e4f-5a6b7c8d9e0f") String id,
        @Schema(description = "Acting user's ID; null for unauthenticated requests", example = "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d47", nullable = true) String userId,
        @Schema(description = "Acting user's email at the time; null when unknown", example = "admin@example.com", nullable = true) String username,
        @Schema(description = "Action, e.g. CREATE, DELETE, SUSPEND, FORCE_CLOSE", example = "SUSPEND") String action,
        @Schema(description = "Resource type, e.g. VOTE, COMMENT, USER", example = "USER") String resourceType,
        @Schema(description = "Affected resource ID; null when not recorded", example = "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d47", nullable = true) String resourceId,
        @Schema(description = "Client IP", example = "203.0.113.7", nullable = true) String ipAddress,
        @Schema(description = "Client User-Agent; null for failed actions", example = "Mozilla/5.0", nullable = true) String userAgent,
        @Schema(description = "Request path; null for failed actions", example = "/api/admin/users/0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d47/suspend", nullable = true) String requestUri,
        @Schema(description = "HTTP method; null for failed actions", example = "PATCH", nullable = true) String httpMethod,
        @Schema(description = "Whether the action succeeded", example = "true") boolean success,
        @Schema(description = "Error message of a failed action; null on success", nullable = true) String errorMessage,
        @Schema(description = "Extra context: the suspend reason (as given) or the new roles (sorted, comma-separated, e.g. `admin,user`); null when none", example = "Repeated spam in comments", nullable = true) String details,
        @Schema(description = "When the action happened", example = "2026-10-16T12:00:00+08:00") OffsetDateTime createdAt
) {
}
