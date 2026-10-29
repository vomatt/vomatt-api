package com.vomatt.admin.dto;

import java.time.OffsetDateTime;

import com.vomatt.entity.ReportReason;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A user's report of a Poll, with the Poll as it is now (Deleted ones keep owner and title)")
public record AdminVoteReportResponse(
        @Schema(description = "Report ID (UUIDv7)", example = "0199f4c2-1a2b-7c3d-8e4f-5a6b7c8d9e0f") String id,
        @Schema(description = "Reported Poll ID", example = "0199f2a3-5b7e-7d40-a1c8-9e3b2f6d4c05") String voteId,
        @Schema(description = "Poll title, also when Deleted", example = "Does garlic cure the flu?") String voteTitle,
        @Schema(description = "Whether the Poll has since been Deleted", example = "false") boolean voteDeleted,
        @Schema(description = "Poll owner's user ID", example = "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d47") String ownerId,
        @Schema(description = "Poll owner's username", example = "troll") String ownerUsername,
        @Schema(description = "Reporter's user ID", example = "0199c1a2-9e4f-7a21-8c3d-1b2a3c4d5e6f") String reporterId,
        @Schema(description = "Reporter's username", example = "alice") String reporterUsername,
        @Schema(description = "Reason given", example = "MISINFORMATION") ReportReason reason,
        @Schema(description = "Reporter's note; null when none was given", example = "The options spread a false health claim.", nullable = true)
        String note,
        @Schema(description = "When it was reported", example = "2026-10-26T10:00:00+08:00") OffsetDateTime createdAt
) {
}
