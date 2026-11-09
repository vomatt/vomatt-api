package com.vomatt.votes.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Owner of an Invite-only Poll, shown on an Invitation link before the caller can reach the Poll")
public record VoteCreatorResponse(
        @Schema(description = "Username of the Poll owner", example = "alice") String username,
        @Schema(description = "Avatar as `bot:<kind>` (the bot cast) or `/api/avatars/<id>.jpg` (an uploaded image, a path on the API origin). Older accounts may hold an image URL; null if never set", example = "bot:boba", nullable = true) String avatarUrl,
        @Schema(description = "Whether the owner is an official account (a group's page, shown with a badge)", example = "false") boolean official
) {
}
