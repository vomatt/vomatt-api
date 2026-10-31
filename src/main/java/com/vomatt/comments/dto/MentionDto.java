package com.vomatt.comments.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A user a Comment @mentions: link `@username` in the text to this user; other handles are plain text")
public record MentionDto(
        @Schema(description = "ID of the mentioned user", example = "0199b8e4-1c2d-7f60-8a3b-6d4e2f1a9b05")
        String userId,
        @Schema(description = "The handle as written in the text, without `@` (the user's current username)", example = "alice")
        String username) {
}
