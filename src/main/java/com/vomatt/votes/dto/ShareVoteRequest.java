package com.vomatt.votes.dto;

import com.vomatt.entity.ShareChannel;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Where the caller shared the Poll's link")
public record ShareVoteRequest(
        @Schema(description = "Share target: COPY_LINK, NATIVE (system share sheet), LINE, FACEBOOK, X, THREADS or OTHER",
                example = "LINE", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Share channel is required")
        ShareChannel channel
) {}
