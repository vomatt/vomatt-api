package com.vomatt.votes.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Body to add Invitees to an Invite-only Poll")
public class InviteesRequest {

    @NotEmpty(message = "At least one user must be invited")
    @Size(max = 100, message = "Maximum 100 users can be invited at once")
    @Schema(description = "1-100 User IDs to invite (e.g. picked via `GET /api/users/search`); null elements are rejected. Users already invited, duplicates and the owner's "
            + "own ID are ignored; unknown or suspended users are rejected with 400 `vote.invitee.ids_invalid`",
            example = "[\"0199f2a1-2c5d-7f13-9b0e-7d4a1e3f6c82\"]", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<@NotNull(message = "Invitee ID cannot be null") UUID> userIds;
}
