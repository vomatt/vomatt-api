package com.vomatt.votes.dto;

import java.time.OffsetDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "An Invitee of an Invite-only Poll, as seen by its owner: a user added by ID, or an email address (never revealing whether it belongs to an account)")
public record InviteeResponse(
        @Schema(description = "User ID of an Invitee added by ID; always null for one added by email", example = "0199f2a1-2c5d-7f13-9b0e-7d4a1e3f6c82", nullable = true) String userId,
        @Schema(description = "Username of an Invitee added by ID; always null for one added by email", example = "bob", nullable = true) String username,
        @Schema(description = "Email address an Invitee was added by, for its whole life; null for one added by ID",
                example = "dave@example.com", nullable = true) String email,
        @Schema(description = "Whether the Invitee holds a Ballot; one who does cannot be removed. Never tells which option. For an email Invitee, true once that person casts a Ballot", example = "false") boolean hasBallot,
        @Schema(description = "When the Invitee was added", example = "2026-10-09T15:20:31+08:00") OffsetDateTime invitedAt
) {
}
