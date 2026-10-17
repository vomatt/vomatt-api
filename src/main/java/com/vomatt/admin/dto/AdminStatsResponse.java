package com.vomatt.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Back-office dashboard counts. Poll counts exclude Deleted Polls except `pollsDeleted`")
public record AdminStatsResponse(
        @Schema(description = "All accounts, suspended included", example = "1520") long usersTotal,
        @Schema(description = "Accounts created in the last 7 days", example = "84") long usersNewLast7Days,
        @Schema(description = "Suspended accounts", example = "3") long usersSuspended,
        @Schema(description = "Open Polls (started, not yet Ended)", example = "57") long pollsInProgress,
        @Schema(description = "Ended Polls, Closed and Cancelled ones included", example = "910") long pollsEnded,
        @Schema(description = "Deleted Polls", example = "12") long pollsDeleted,
        @Schema(description = "Ballots cast or changed in the last 24 hours", example = "433") long ballotsLast24Hours
) {
}
