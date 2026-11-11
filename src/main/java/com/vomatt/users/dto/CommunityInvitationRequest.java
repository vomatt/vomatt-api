package com.vomatt.users.dto;

import com.vomatt.entity.CommunityRole;
import com.vomatt.entity.User;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

@Schema(description = "Invite a user, by id or by email address (exactly one), to a community account")
public record CommunityInvitationRequest(
        @Schema(description = "The invitee's user id, e.g. from user search; send this or `email`", example = "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d47", nullable = true)
        UUID userId,
        @Schema(description = "The invitee's email address; send this or `userId`", example = "dave@example.com", nullable = true)
        @Email @Size(max = 254) String email,
        @Schema(description = "Role the invitee gets: OWNER, ADMIN, CHIEF_EDITOR, EDITOR or ANALYST; only an OWNER grants OWNER", example = "EDITOR")
        @NotNull CommunityRole role
) {
    // Normalized before validation, as the Invite List request does, so surrounding spaces and case are accepted
    public CommunityInvitationRequest {
        email = email == null || email.isBlank() ? null : User.normalizeEmail(email);
    }

    @Schema(hidden = true)
    @AssertTrue(message = "Send exactly one of userId and email")
    public boolean isOneInvitee() {
        return (userId == null) != (email == null);
    }
}
