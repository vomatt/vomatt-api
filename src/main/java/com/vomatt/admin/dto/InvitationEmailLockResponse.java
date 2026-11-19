package com.vomatt.admin.dto;

import java.time.OffsetDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A sender locked out of emailing sign-up invitations to an address")
public record InvitationEmailLockResponse(
        @Schema(description = "The sender's user id (the real member when they acted as a community account)",
                example = "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d47") String senderId,
        @Schema(description = "The sender's username", example = "alice") String senderUsername,
        @Schema(description = "The locked address, normalized", example = "dave@example.com") String email,
        @Schema(description = "When the lock ends on its own", example = "2026-11-26T14:52:00+08:00") OffsetDateTime lockedUntil
) {}
