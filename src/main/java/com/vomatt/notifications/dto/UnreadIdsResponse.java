package com.vomatt.notifications.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Ids of unread notifications in the feed")
public record UnreadIdsResponse(
        @ArraySchema(arraySchema = @Schema(description = "Ids (`NotificationResponse.id`) of unread notifications, newest first"),
                schema = @Schema(example = "comment_0199f2a5-1c2d-7e3f-8a4b-5c6d7e8f9a0b"))
        List<String> ids) {
}
