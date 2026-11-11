package com.vomatt.users.dto;

import com.vomatt.entity.CommunityRole;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

@Schema(description = "An invitation to join a community account, sent to a user (`userId` set) or to an email address (`email` set)")
public record CommunityInvitationResponse(
        @Schema(description = "Invitation id", example = "019a6b2c-3d4e-7f50-8a61-b2c3d4e5f607") String id,
        @Schema(description = "The invited user's id; null for an invitation by email", example = "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d47", nullable = true) String userId,
        @Schema(description = "The invited user's username; null for an invitation by email", example = "dave", nullable = true) String username,
        @Schema(description = "The invited user's display name; null if never set or for an invitation by email", example = "Dave", nullable = true) String displayName,
        @Schema(description = "The invited user's avatar; null if never set or for an invitation by email", example = "bot:boba", nullable = true) String avatarUrl,
        @Schema(description = "The invited address, normalized; null for an invitation by user id. Never tells whether it has an account", example = "dave@example.com", nullable = true) String email,
        @Schema(description = "Role the invitee gets on joining", example = "EDITOR") CommunityRole role,
        @Schema(description = "When it was sent", example = "2026-11-11T14:52:00+08:00") OffsetDateTime invitedAt,
        @Schema(description = "When it stops being usable, 7 days after it was sent; an expired one is listed until the user or address is invited again", example = "2026-11-18T14:52:00+08:00") OffsetDateTime expiresAt
) {}
