package com.vomatt.groups.dto;

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
@Schema(description = "Body to add members to a Group; at least one of `userIds` / `emails` must be non-empty")
public class GroupMembersRequest {

    @Size(max = 100, message = "Maximum 100 users can be added at once")
    @Schema(description = "Up to 100 User IDs to add (e.g. picked via `GET /api/users/search`); null elements are rejected. Members already in the Group, duplicates and the owner's "
            + "own ID are ignored; unknown or suspended users are rejected with 400 `group.member.ids_invalid`",
            example = "[\"0199f2a1-2c5d-7f13-9b0e-7d4a1e3f6c82\"]", nullable = true)
    private List<@NotNull(message = "Member ID cannot be null") UUID> userIds;

    @Size(max = 100, message = "Maximum 100 email addresses can be added at once")
    @Schema(description = "Up to 100 email addresses to add; null elements and invalid addresses are rejected. Lower-cased and trimmed like sign-in does; "
            + "addresses already in the Group, duplicates and the owner's own address are ignored. Each address is listed as entered, "
            + "whether or not it belongs to an account; nobody is notified",
            example = "[\"carol@example.com\"]", nullable = true)
    private List<@NotNull(message = "Member email cannot be null") @Email(message = "Invalid member email") @Size(max = 254, message = "Member email cannot exceed 254 characters") String> emails;

    // Normalized like sign-in before validation, so padded or upper-case input is accepted and stored the same way
    public void setEmails(List<String> emails) {
        this.emails = emails == null ? null : emails.stream().map(e -> e == null ? null : User.normalizeEmail(e)).toList();
    }

    @AssertTrue(message = "At least one user or email must be added")
    @Schema(hidden = true)
    public boolean isAnyMember() {
        return (userIds != null && !userIds.isEmpty()) || (emails != null && !emails.isEmpty());
    }
}
