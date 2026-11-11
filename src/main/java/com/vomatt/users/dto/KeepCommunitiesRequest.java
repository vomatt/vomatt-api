package com.vomatt.users.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.UUID;

@Schema(description = "The community accounts to keep unlocked; every other one the caller owns is locked")
public record KeepCommunitiesRequest(
        @NotEmpty
        @ArraySchema(arraySchema = @Schema(description = "Ids of community accounts the caller is an OWNER of: exactly `communityQuota.limit`, or all of them when the caller owns fewer; no id twice"),
                schema = @Schema(example = "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d48"))
        List<UUID> communityIds
) {}
