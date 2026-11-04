package com.vomatt.notifications.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Ids of unread notifications in the feed")
public record UnreadIdsResponse(
        @ArraySchema(arraySchema = @Schema(description = "Ids (`NotificationResponse.id`) of the notifications `GET /api/notifications` "
                + "lists with `read` false, of the kinds the caller's preferences leave on (only the requested `category`'s when given), "
                + "newest first; at most 100, the newest ones"),
                schema = @Schema(example = "comment_0199f2a5-1c2d-7e3f-8a4b-5c6d7e8f9a0b"))
        List<String> ids) {
}
