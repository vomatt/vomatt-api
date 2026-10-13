package com.vomatt.votes.dto;

import java.time.OffsetDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "An Invitee of an Invite-only Poll, as seen by its owner")
public record InviteeResponse(
        @Schema(description = "User ID of the Invitee", example = "0199f2a1-2c5d-7f13-9b0e-7d4a1e3f6c82") String userId,
        @Schema(description = "Username of the Invitee", example = "bob") String username,
        @Schema(description = "Whether the Invitee holds a Ballot; one who does cannot be removed. Never tells which option", example = "false") boolean hasBallot,
        @Schema(description = "When the Invitee was added", example = "2026-10-09T15:20:31+08:00") OffsetDateTime invitedAt
) {
}
