package com.vomatt.votes.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Body to add Invitees to an Invite-only Poll; at least one of `userIds` / `emails` must be non-empty")
public class InviteesRequest {

    @Size(max = 100, message = "Maximum 100 users can be invited at once")
    @Schema(description = "Up to 100 User IDs to invite (e.g. picked via `GET /api/users/search`); null elements are rejected. Users already invited, duplicates and the owner's "
            + "own ID are ignored; unknown or suspended users are rejected with 400 `vote.invitee.ids_invalid`",
            example = "[\"0199f2a1-2c5d-7f13-9b0e-7d4a1e3f6c82\"]", nullable = true)
    private List<@NotNull(message = "Invitee ID cannot be null") UUID> userIds;

    @Size(max = 100, message = "Maximum 100 email addresses can be invited at once")
    @Schema(description = "Up to 100 email addresses to invite; null elements and invalid addresses are rejected. Lower-cased and trimmed like sign-in does; "
            + "addresses already invited, duplicates and the owner's own address are ignored. An address of a registered user invites that user "
            + "(a suspended one is rejected with 400 `vote.invitee.ids_invalid`); any other address becomes a pending email Invitee and gets an invitation email",
            example = "[\"carol@example.com\"]", nullable = true)
    private List<@NotNull(message = "Invitee email cannot be null") @Email(message = "Invalid invitee email") @Size(max = 254, message = "Invitee email cannot exceed 254 characters") String> emails;

    @AssertTrue(message = "At least one user or email must be invited")
    @Schema(hidden = true)
    public boolean isAnyInvitee() {
        return (userIds != null && !userIds.isEmpty()) || (emails != null && !emails.isEmpty());
    }
}
