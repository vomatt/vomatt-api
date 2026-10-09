package com.vomatt.notifications.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Number of unread Ended Notifications")
public record UnreadCountResponse(@Schema(description = "Ended Polls the caller owns or holds a Ballot in that are not yet marked read", example = "3") long count) {
}
