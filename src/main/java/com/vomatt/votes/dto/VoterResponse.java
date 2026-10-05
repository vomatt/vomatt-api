package com.vomatt.votes.dto;

import java.time.OffsetDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "已結束 Poll 的投票者與其選擇")
public record VoterResponse(
        @Schema(description = "使用者 ID") String userId,
        @Schema(description = "使用者名稱") String username,
        @Schema(description = "選擇的選項 ID") String optionId,
        @Schema(description = "投票時間") OffsetDateTime votedAt
) {
}
