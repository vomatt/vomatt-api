package com.vomatt.users.dto;

import com.vomatt.entity.CommunityRole;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

@Schema(description = "An invitation to join a community account, sent to an email address")
public record CommunityInvitationResponse(
        @Schema(description = "Invitation id", example = "019a6b2c-3d4e-7f50-8a61-b2c3d4e5f607") String id,
        @Schema(description = "The invited address, normalized", example = "dave@example.com") String email,
        @Schema(description = "Role the invitee gets on joining", example = "EDITOR") CommunityRole role,
        @Schema(description = "When it was sent", example = "2026-11-11T14:52:00+08:00") OffsetDateTime invitedAt,
        @Schema(description = "When it stops being usable, 7 days after it was sent; an expired one is listed until the address is invited again", example = "2026-11-18T14:52:00+08:00") OffsetDateTime expiresAt
) {}
