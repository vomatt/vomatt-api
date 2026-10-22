package com.vomatt.invitelists.dto;

import java.time.OffsetDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "An Invite List of the caller (only its owner ever sees it)")
public record InviteListResponse(
        @Schema(description = "Invite List ID; pass it in `inviteListIds` when creating or editing an Invite-only Poll or adding Invitees", example = "0199f2b0-7a1c-7e45-9b3d-2c8f6a4e1d07") String id,
        @Schema(description = "Invite List name", example = "Book club") String name,
        @Schema(description = "Number of members (users and email addresses)", example = "3") long memberCount,
        @Schema(description = "When the Invite List was created", example = "2026-10-09T15:20:31+08:00") OffsetDateTime createdAt
) {
}
