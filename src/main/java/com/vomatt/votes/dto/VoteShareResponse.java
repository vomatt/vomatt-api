package com.vomatt.votes.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A Poll's share count after the caller's share")
public record VoteShareResponse(
        @Schema(description = "Number of distinct users who shared the Poll, the caller included", example = "18")
        long shareCount
) {}
