package com.vomatt.votes.dto;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Body to cast a Ballot")
public class VoteRequest {
    
    @NotEmpty(message = "At least one option must be selected")
    @Schema(description = "The chosen option ID. Polls are single-choice: send exactly one ID (more than one is rejected with 400 `vote.multiple.not_allowed`)",
            example = "[\"0199f2a4-8d11-7c3e-b0a4-5e29c7d6f813\"]", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<String> optionIds;

    @Schema(description = "Cast an Anonymous Ballot: left out of `GET /api/votes/{voteId}/voters` whatever the Poll's `voterVisibility`. "
            + "null or omitted keeps the current Ballot's flag, and a first Ballot uses the caller's `anonymousBallotByDefault`; "
            + "a Ballot cast after a Retraction is a first Ballot again, so it starts from `anonymousBallotByDefault`",
            example = "false", nullable = true)
    private Boolean anonymous;
}