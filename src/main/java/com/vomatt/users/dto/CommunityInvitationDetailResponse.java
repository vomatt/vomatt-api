package com.vomatt.users.dto;

import com.vomatt.entity.CommunityRole;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

@Schema(description = "An invitation to join a community account as its invitee sees it")
public record CommunityInvitationDetailResponse(
        @Schema(description = "Invitation id", example = "019a6b2c-3d4e-7f50-8a61-b2c3d4e5f607") String id,
        @Schema(description = "Role the invitee gets on joining", example = "EDITOR") CommunityRole role,
        @Schema(description = "When it stops being usable, 7 days after it was sent", example = "2026-11-18T14:52:00+08:00") OffsetDateTime expiresAt,
        @Schema(description = "The community account the invitation is to") UserDto community,
        @Schema(description = "The member who sent it; null once their account is deleted", nullable = true) UserDto inviter,
        @Schema(description = "Whether the community account is locked: the invitation can be seen and declined, but accept answers 403 `community.locked` until it is unlocked", example = "false") boolean locked,
        @Schema(description = "Whether the caller is already a member: accepting keeps their current role and uses the invitation up", example = "false") boolean member
) {}
