package com.vomatt.votes.dto;

import java.time.OffsetDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A Participant of an Ended Poll and, when the Poll holds no Anonymous Ballot, the option they chose")
public record VoterResponse(
        @Schema(description = "User ID of the Participant", example = "0199f2a1-1b4c-7e02-8a9d-6c3f0d2e5b71") String userId,
        @Schema(description = "Username of the Participant", example = "alice") String username,
        @Schema(description = "Display name of the Participant; null if they hid it (public by default) or never set one", example = "Alice", nullable = true) String displayName,
        @Schema(description = "First name of the Participant; null if they hid it (hidden by default) or never set one", example = "Alice", nullable = true) String firstName,
        @Schema(description = "Last name of the Participant; null if they hid it (hidden by default) or never set one", example = "Chen", nullable = true) String lastName,
        @Schema(description = "Avatar as `bot:<kind>` (the bot cast) or `/api/avatars/<id>.jpg` (an uploaded image, a path on the API origin). Older accounts may hold an image URL; null if never set", example = "bot:boba", nullable = true) String avatarUrl,
        @Schema(description = "ID of the option the Participant chose. Null for every Participant while the Poll holds an Anonymous Ballot other than the caller's own, "
                + "so the anonymous choices cannot be worked out from the named ones and the option totals", example = "0199f2a4-8d11-7c3e-b0a4-5e29c7d6f813", nullable = true) String optionId,
        @Schema(description = "When the Participant's current Ballot was cast (a changed Ballot gets a new time)", example = "2026-10-15T20:41:07+08:00") OffsetDateTime votedAt
) {
}
