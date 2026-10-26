package com.vomatt.notifications.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Notification switches to change; an omitted or null field keeps its current value")
public record UpdateNotificationPreferencesRequest(
        @Schema(description = "RESULT and CANCELLED", example = "true", nullable = true) Boolean resultsIn,
        @Schema(description = "INVITATION", example = "true", nullable = true) Boolean invitations,
        @Schema(description = "COMMENT", example = "false", nullable = true) Boolean comments,
        @Schema(description = "CLOSING_SOON", example = "true", nullable = true) Boolean closingSoon
) {}
