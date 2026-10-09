package com.vomatt.votes.dto;

import java.time.OffsetDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A Participant of an Ended Poll and the option they chose")
public record VoterResponse(
        @Schema(description = "User ID of the Participant", example = "0199f2a1-1b4c-7e02-8a9d-6c3f0d2e5b71") String userId,
        @Schema(description = "Username of the Participant", example = "alice") String username,
        @Schema(description = "ID of the option the Participant chose", example = "0199f2a4-8d11-7c3e-b0a4-5e29c7d6f813") String optionId,
        @Schema(description = "When the Participant's current Ballot was cast (a changed Ballot gets a new time)", example = "2026-10-15T20:41:07+08:00") OffsetDateTime votedAt
) {
}
