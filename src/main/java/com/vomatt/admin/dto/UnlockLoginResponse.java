package com.vomatt.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Result of clearing a user's login lockouts")
public record UnlockLoginResponse(
        @Schema(description = "How many lock, counter and cooldown entries were removed; 0 means nothing matched (already unlocked)", example = "3")
        int clearedEntries
) {
}
