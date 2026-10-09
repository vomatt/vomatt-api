package com.vomatt.votes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "Whether the caller holds a Ballot in a Poll, and its Selection")
public class UserVoteStatusResponse {
    @Schema(description = "True when the caller holds a Ballot in the Poll (is a Participant)", example = "true")
    private boolean hasVoted;

    @Schema(description = "Option IDs of the caller's Selection. Polls are single-choice, so it holds exactly one ID when `hasVoted` is true and is empty otherwise",
            example = "[\"0199f2a4-8d11-7c3e-b0a4-5e29c7d6f813\"]")
    private List<String> selectedOptions;
}
