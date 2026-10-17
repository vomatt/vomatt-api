package com.vomatt.votes.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Owner of an Invite-only Poll, shown on an Invitation link before the caller can reach the Poll")
public record VoteCreatorResponse(
        @Schema(description = "Username of the Poll owner", example = "alice") String username,
        @Schema(description = "Avatar as `bot:<kind>` (the bot cast). Older accounts may hold an image URL; null if never set", example = "bot:boba", nullable = true) String avatarUrl
) {
}
