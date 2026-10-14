package com.vomatt.groups.dto;

import java.time.OffsetDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A Group of the caller (only its owner ever sees it)")
public record GroupResponse(
        @Schema(description = "Group ID; pass it in `groupIds` when creating or editing an Invite-only Poll or adding Invitees", example = "0199f2b0-7a1c-7e45-9b3d-2c8f6a4e1d07") String id,
        @Schema(description = "Group name", example = "Book club") String name,
        @Schema(description = "Number of members (users and email addresses)", example = "3") long memberCount,
        @Schema(description = "When the Group was created", example = "2026-10-09T15:20:31+08:00") OffsetDateTime createdAt
) {
}
