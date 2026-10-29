package com.vomatt.notifications.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Which notification kinds appear in the feed and the unread count. FOLLOWED has no switch")
public record NotificationPreferencesResponse(
        @Schema(description = "RESULT and CANCELLED; default true", example = "true") boolean resultsIn,
        @Schema(description = "INVITATION; default true", example = "true") boolean invitations,
        @Schema(description = "COMMENT; default true", example = "true") boolean comments,
        @Schema(description = "CLOSING_SOON; default false", example = "false") boolean closingSoon,
        @Schema(description = "MENTION; default true", example = "true") boolean mentions,
        @Schema(description = "LIKE; default true", example = "true") boolean likes
) {}
