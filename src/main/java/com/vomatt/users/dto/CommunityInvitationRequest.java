package com.vomatt.users.dto;

import com.vomatt.entity.CommunityRole;
import com.vomatt.entity.User;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "Invite an email address to a community account")
public record CommunityInvitationRequest(
        @Schema(description = "The invitee's email address", example = "dave@example.com")
        @NotBlank @Email @Size(max = 254) String email,
        @Schema(description = "Role the invitee gets: OWNER, ADMIN, CHIEF_EDITOR, EDITOR or ANALYST; only an OWNER grants OWNER", example = "EDITOR")
        @NotNull CommunityRole role
) {
    // Normalized before validation, as the Invite List request does, so surrounding spaces and case are accepted
    public CommunityInvitationRequest {
        email = email == null ? null : User.normalizeEmail(email);
    }
}
