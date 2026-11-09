package com.vomatt.reports;

import static com.vomatt.common.config.OpenAPIConfig.ERR_CODE;
import static com.vomatt.common.config.OpenAPIConfig.ERR_END;
import static com.vomatt.common.config.OpenAPIConfig.ERR_ERROR;
import static com.vomatt.common.config.OpenAPIConfig.ERR_MESSAGE;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.vomatt.common.annotation.CommonApiResponses;
import com.vomatt.common.config.OpenAPIConfig;
import com.vomatt.common.response.ApiResponse;
import com.vomatt.common.security.UserPrincipal;
import com.vomatt.reports.dto.PollReportResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/votes")
@RequiredArgsConstructor
@Tag(name = "Poll report", description = "Result report of an Ended Pro poll: turnout, results, breakdowns by age band and gender, no names")
public class PollReportController {

    private static final String ACCESS = """
            **Auth**: required. Readers: the Poll's owner; for a Poll of an official account, members acting as it (`X-Act-As`) whose role may read reports (`OWNER`, `ADMIN`, `ANALYST`; not `EDITOR`)
            **Precondition**: the Poll is a Pro poll (`pro`), has Ended and its `revealAt` (if set) has passed
            """;
    private static final String BEHAVIOR = """
            **Behavior**: the report is a snapshot taken once after the Poll Ended (on the first read, or by the daily job) and never recomputed, \
            so later profile changes do not move it; `generatedAt` tells when it was taken. Age is the voter's age at the Poll's end time (Asia/Taipei) from their birthday. \
            Small-cell suppression per breakdown: every group with fewer voters than `minCellSize` (admin `config` lookup `report_min_cell_size`, default 5) is merged into `MERGED`; \
            while `MERGED` is still smaller, the next smallest groups join it, and if all voters together are fewer, `MERGED` has null `voters` and `counts`. \
            Option totals are not suppressed. Pre-Pro Anonymous Ballots count like any Ballot; no user id or name appears.
            **Side effects**: the first read after the Poll Ended stores the snapshot
            """;
    private static final String ERRORS = """
            **Errors**:
            - 404 `vote.not_found`: Poll does not exist or is Deleted
            - 403 `vote.forbidden`: the caller is not the Poll's owner
            - 403 `official.role_forbidden`: acting as the official account with a role that may not read reports (`EDITOR`)
            - 400 `report.not_pro`: the Poll is not a Pro poll
            - 403 `report.not_ready`: the Poll has not Ended or its `revealAt` has not passed
            """;
    private static final String EX_REPORT = """
            { "voteId": "0199f2a3-5b7e-7d40-a1c8-9e3b2f6d4c05", "title": "Renovate the lobby this year?",
              "generatedAt": "2026-10-20T04:30:00Z", "minCellSize": 5,
              "timeline": { "createdAt": "2026-10-09T07:20:31Z", "openedAt": "2026-10-12T01:00:00Z", "closedAt": "2026-10-19T01:00:00Z", "revealedAt": "2026-10-19T01:00:00Z" },
              "turnout": { "ballots": 24, "invitees": 30, "rate": 0.8 },
              "options": [ { "optionId": "0199f2a4-8d11-7c3e-b0a4-5e29c7d6f813", "text": "Yes", "count": 15 },
                           { "optionId": "0199f2a4-8d12-7f60-9b17-2a4c8e0d5f39", "text": "No", "count": 9 } ],
              "ageBands": [ { "group": "30-39", "voters": 8, "counts": [ 5, 3 ] }, { "group": "40-49", "voters": 10, "counts": [ 6, 4 ] },
                            { "group": "MERGED", "voters": 6, "counts": [ 4, 2 ] } ],
              "genders": [ { "group": "MALE", "voters": 11, "counts": [ 7, 4 ] }, { "group": "FEMALE", "voters": 9, "counts": [ 5, 4 ] },
                           { "group": "MERGED", "voters": null, "counts": null } ] }""";
    private static final String ERR_EXAMPLE = ERR_MESSAGE + "The report is available once the poll has ended and its results are revealed"
            + ERR_CODE + "report.not_ready" + ERR_ERROR + "The report is available once the poll has ended and its results are revealed" + ERR_END;

    private final PollReportService reportService;

    @GetMapping("/{voteId}/report")
    @CommonApiResponses
    @Operation(summary = "Get the result report of a Pro poll", description = ACCESS + BEHAVIOR + ERRORS)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "The report",
            content = @Content(examples = @ExampleObject(name = "Report", value = "{ \"success\": true, \"data\": " + EX_REPORT
                    + ", \"message\": null, \"errorCode\": null, \"error\": null }")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Not the owner (`vote.forbidden`), role may not read reports (`official.role_forbidden`), or not revealed yet (`report.not_ready`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF), examples = @ExampleObject(name = "report.not_ready", value = ERR_EXAMPLE)))
    public ResponseEntity<ApiResponse<PollReportResponse>> getReport(
            @Parameter(description = "Poll ID", required = true, example = "0199f2a3-5b7e-7d40-a1c8-9e3b2f6d4c05") @PathVariable String voteId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(reportService.getReport(voteId, principal.userId())));
    }

    @GetMapping("/{voteId}/report.csv")
    @CommonApiResponses
    @Operation(summary = "Download the result report of a Pro poll as CSV", description = ACCESS + """
            **Behavior**: the same snapshot as `GET /api/votes/{voteId}/report`, as a CSV download for Excel (`text/csv`, UTF-8 with a BOM, CRLF, \
            `Content-Disposition: attachment`). This route alone answers without the `ApiResponse` wrapper; errors are still the JSON `ApiResponse`, \
            so send `Accept: */*` (or include `application/json`). Rows: Poll, Poll ID, Generated at, Created at, Opened at, Closed at, Revealed at, \
            Ballots, Invitees, Turnout rate, Minimum group size; then a blank line and `Option,Ballots` rows; then `Age band,Voters,<option>...` and \
            `Gender,Voters,<option>...` sections (suppressed cells empty). Text starting with `=`, `+`, `-` or `@` gets a leading `'`.
            **Side effects**: the first read after the Poll Ended stores the snapshot
            """ + ERRORS)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "The report as CSV",
            content = @Content(mediaType = "text/csv", schema = @Schema(type = "string"), examples = @ExampleObject(name = "Report", value = """
                    Poll,Renovate the lobby this year?\r
                    Poll ID,0199f2a3-5b7e-7d40-a1c8-9e3b2f6d4c05\r
                    Generated at,2026-10-20T04:30Z\r
                    ...\r
                    Option,Ballots\r
                    Yes,15\r
                    No,9\r
                    \r
                    Age band,Voters,Yes,No\r
                    30-39,8,5,3\r
                    MERGED,,,\r
                    """)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Not the owner (`vote.forbidden`), role may not read reports (`official.role_forbidden`), or not revealed yet (`report.not_ready`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF), examples = @ExampleObject(name = "report.not_ready", value = ERR_EXAMPLE)))
    public ResponseEntity<byte[]> getReportCsv(
            @Parameter(description = "Poll ID", required = true, example = "0199f2a3-5b7e-7d40-a1c8-9e3b2f6d4c05") @PathVariable String voteId,
            @AuthenticationPrincipal UserPrincipal principal) {
        byte[] csv = PollReportService.toCsv(reportService.getReport(voteId, principal.userId()));
        // Content type set here, not via produces: an error must still render as the JSON ApiResponse
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("poll-report-" + voteId + ".csv").build().toString())
                .body(csv);
    }
}
