package com.vomatt.admin.dto;

import java.time.OffsetDateTime;

import com.vomatt.votes.dto.VoteResponse;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A Poll as seen in the back office: the regular Poll (results still Sealed until it has Ended) plus moderation fields")
public record AdminVoteResponse(
        @Schema(description = "The Poll, as on the public endpoints; caller-specific fields (`myOptionId`, `deletable`, `unread`) are null") VoteResponse vote,
        @Schema(description = "When the Poll was Deleted; null while not Deleted", example = "2026-10-16T12:00:00+08:00", nullable = true) OffsetDateTime deletedAt,
        @Schema(description = "Email of the owner; null for accounts registered by phone", example = "alice@example.com", nullable = true) String creatorEmail,
        @Schema(description = "False when the owner is suspended", example = "true") boolean creatorActive
) {
}
