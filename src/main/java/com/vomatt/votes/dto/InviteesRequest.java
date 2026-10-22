package com.vomatt.votes.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;
import com.vomatt.entity.User;

import lombok.Data;

@Data
@Schema(description = "Body to add Invitees to an Invite-only Poll; at least one of `userIds` / `emails` / `inviteListIds` must be non-empty")
public class InviteesRequest {

    @Size(max = 100, message = "Maximum 100 users can be invited at once")
    @Schema(description = "Up to 100 User IDs to invite (e.g. picked via `GET /api/users/search`); null elements are rejected. Users already invited, duplicates and the owner's "
            + "own ID are ignored; unknown or suspended users are rejected with 400 `vote.invitee.ids_invalid`",
            example = "[\"0199f2a1-2c5d-7f13-9b0e-7d4a1e3f6c82\"]", nullable = true)
    private List<@NotNull(message = "Invitee ID cannot be null") UUID> userIds;

    @Size(max = 100, message = "Maximum 100 email addresses can be invited at once")
    @Schema(description = "Up to 100 email addresses to invite; null elements and invalid addresses are rejected. Normalized like sign-in does (lower-cased, trimmed, Gmail in canonical form); "
            + "addresses already invited, duplicates and the owner's own address are ignored. Each address is listed as entered and gets an invitation email; "
            + "an active account's user gets access right away, any other address once someone verifies it at sign-up / sign-in",
            example = "[\"carol@example.com\"]", nullable = true)
    private List<@NotNull(message = "Invitee email cannot be null") @Email(message = "Invalid invitee email") @Size(max = 254, message = "Invitee email cannot exceed 254 characters") String> emails;

    @Size(max = 20, message = "Maximum 20 Invite Lists can be invited at once")
    @Schema(description = "Up to 20 IDs of the caller's Invite Lists (see `GET /api/invite-lists`) whose current members to invite; null elements are rejected and "
            + "a missing Invite List or someone else's is 404 `invite_list.not_found`. Members added by ID join `userIds` (suspended ones are skipped) and members added "
            + "by email join `emails`, by their stored address, with the same rules and invitation emails. The members are copied now: later Invite List changes never touch the Poll",
            example = "[\"0199f2b0-7a1c-7e45-9b3d-2c8f6a4e1d07\"]", nullable = true)
    private List<@NotNull(message = "Invite List ID cannot be null") UUID> inviteListIds;

    // Normalized like sign-in before validation, so padded or upper-case input is accepted and stored the same way
    public void setEmails(List<String> emails) {
        this.emails = emails == null ? null : emails.stream().map(e -> e == null ? null : User.normalizeEmail(e)).toList();
    }

    @AssertTrue(message = "At least one user, email or Invite List must be invited")
    @Schema(hidden = true)
    public boolean isAnyInvitee() {
        return (userIds != null && !userIds.isEmpty()) || (emails != null && !emails.isEmpty())
                || (inviteListIds != null && !inviteListIds.isEmpty());
    }
}
