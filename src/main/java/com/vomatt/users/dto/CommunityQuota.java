package com.vomatt.users.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

@Schema(description = "How many community accounts the user owns against the plan's limit")
public record CommunityQuota(
        @Schema(description = "Active, unlocked community accounts the user is an OWNER of; a suspended one does not count", example = "3") int owned,
        @Schema(description = "The most the plan allows; creating, receiving ownership, being granted OWNER or unlocking "
                + "one at this count answers 403 `community.limit_reached`", example = "1") int limit,
        @Schema(description = "True after a downgrade left the user owning more than `limit`: ask them which to keep "
                + "(`POST /api/users/me/communities/keep`)", example = "true") boolean selectionRequired,
        @Schema(description = "When the earliest created are kept and the rest locked if the user has not chosen; null "
                + "unless `selectionRequired`", example = "2026-11-18T10:00:00Z", nullable = true) OffsetDateTime selectionDeadline
) {}
