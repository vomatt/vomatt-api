package com.vomatt.notifications.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Number of unread notifications in the feed")
public record UnreadCountResponse(@Schema(description = "Unread notifications of the kinds the caller's preferences leave on (only the requested "
        + "`category`'s when given), as listed by GET /api/notifications; capped at 100 (100 means 100 or more)", example = "3") long count) {
}
