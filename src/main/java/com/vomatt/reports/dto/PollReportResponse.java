package com.vomatt.reports.dto;

import java.time.OffsetDateTime;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Result report of an Ended Pro poll: a snapshot taken once after the Poll Ended, aggregates only (no user ids or names)")
public record PollReportResponse(
        @Schema(description = "Poll ID") String voteId,
        @Schema(description = "Poll title") String title,
        @Schema(description = "When the snapshot was taken; later reads return the same snapshot") OffsetDateTime generatedAt,
        @Schema(description = "Smallest group shown in a breakdown (`report_min_cell_size` at generation time)") int minCellSize,
        Timeline timeline,
        Turnout turnout,
        @Schema(description = "Every option in display order with its Ballot count; `counts` in each breakdown group follow this order") List<OptionCount> options,
        @Schema(description = "Ballots by the voter's age at the Poll's end time (Asia/Taipei): `UNDER_18`, `18-29`, `30-39`, `40-49`, `50-59`, `60+`, `NOT_PROVIDED`, then `MERGED`. Groups without voters are left out") List<Group> ageBands,
        @Schema(description = "Ballots by gender: `MALE`, `FEMALE`, `OTHER`, `UNDISCLOSED`, `NOT_PROVIDED`, then `MERGED`. Groups without voters are left out") List<Group> genders) {

    @Schema(description = "Poll timeline")
    public record Timeline(
            @Schema(description = "When the Poll was created") OffsetDateTime createdAt,
            @Schema(description = "When the Poll opened (`startTime`)") OffsetDateTime openedAt,
            @Schema(description = "When the Poll Ended (`endTime`)") OffsetDateTime closedAt,
            @Schema(description = "When results were revealed (`revealAt`, or `endTime` when unset)") OffsetDateTime revealedAt) {
    }

    @Schema(description = "Turnout")
    public record Turnout(
            @Schema(description = "Ballots cast, the Poll's `turnout`; the owner's Ballot included") int ballots,
            @Schema(description = "Distinct Invitees of an INVITE_ONLY Poll (a user invited both by ID and by an address of theirs counts once); null for other Audiences", nullable = true) Integer invitees,
            @Schema(description = "Invitees holding a Ballot divided by `invitees` (0-1; the owner's Ballot never counts); null unless INVITE_ONLY with Invitees", nullable = true) Double rate) {
    }

    @Schema(description = "One option's Ballot count, the same stored count the Poll's results show")
    public record OptionCount(String optionId, String text, int count) {
    }

    @Schema(description = "One breakdown group. Every group under `minCellSize` voters is merged into `MERGED`; when that is still under it, "
            + "the next smallest groups join it until it reaches it. If even all voters together are under it, `MERGED` has null `voters` and `counts`")
    public record Group(
            @Schema(description = "Group key", example = "18-29") String group,
            @Schema(description = "Voters in the group; null when suppressed", nullable = true) Integer voters,
            @Schema(description = "Ballots per option, in `options` order; null when suppressed", nullable = true) List<Integer> counts) {
    }
}
