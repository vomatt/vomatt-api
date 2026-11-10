package com.vomatt.users.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "How many community accounts the user owns against the plan's limit")
public record CommunityQuota(
        @Schema(description = "Community accounts the user is an OWNER of", example = "1") int owned,
        @Schema(description = "The most the plan allows; creating, receiving ownership or being granted OWNER at this "
                + "count answers 403 `community.limit_reached`", example = "1") int limit
) {}
