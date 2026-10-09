package com.vomatt.votes;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.vomatt.votes.dto.CreateVoteRequest;
import com.vomatt.votes.dto.VoteRequest;
import com.vomatt.common.response.ApiResponse;
import com.vomatt.common.response.CursorResponse;
import com.vomatt.common.response.SimpleResultResponse;
import com.vomatt.common.annotation.CommonApiResponses;
import com.vomatt.common.annotation.PublicApiResponse;
import com.vomatt.votes.dto.UserVoteStatusResponse;
import com.vomatt.votes.dto.VoteResponse;
import com.vomatt.votes.dto.VoteResultResponse;
import com.vomatt.votes.dto.VoterResponse;
import com.vomatt.common.security.UserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.vomatt.votes.VoteService;
import com.vomatt.common.audit.Auditable;

import com.vomatt.common.config.OpenAPIConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
@RequestMapping("/api/votes")
@RequiredArgsConstructor
@Tag(name = "Vote", description = "Poll APIs: create, edit, Close, cast / retract a Ballot, results, Participants, Feed / Explore / Search listing")
public class VoteController {
    private static final Logger log = LoggerFactory.getLogger(VoteController.class);
    private final VoteService voteService;

    private static final String ERR = "#/components/schemas/" + OpenAPIConfig.ERROR_SCHEMA;

    // Example bodies; pieces are concatenated so that every example stays a compile-time constant
    private static final String ERR_1 = "{ \"success\": false, \"data\": null, \"message\": \"";
    private static final String ERR_2 = "\", \"errorCode\": \"";
    private static final String ERR_3 = "\", \"error\": \"Same text as message\" }";

    private static final String POLL_1 = """
            { "success": true, "data": {
                "id": "0199f2a3-5b7e-7d40-a1c8-9e3b2f6d4c05",
                "title": "Which language should we use for the next side project?",
                "description": "Vote before Friday.",
                "creatorId": "0199f2a1-1b4c-7e02-8a9d-6c3f0d2e5b71",
                "creatorUsername": "alice",
                "startTime": "2026-10-12T09:00:00+08:00",
                "endTime": "2026-10-19T09:00:00+08:00",
                "active": true,
                "allowMultipleChoices": false,
                "anonymous": false,
                "createdAt": "2026-10-09T15:20:31+08:00",
                "updatedAt": "2026-10-09T15:20:31+08:00",
            """;
    private static final String POLL_COUNTS_NONE = "    \"totalVotes\": 0, \"participantCount\": 0, \"votingActive\": false,\n";
    private static final String POLL_COUNTS_OPEN = "    \"totalVotes\": 42, \"participantCount\": 42, \"votingActive\": true,\n";
    private static final String POLL_COUNTS_ENDED = "    \"totalVotes\": 42, \"participantCount\": 42, \"votingActive\": false,\n";
    private static final String POLL_OPTIONS_SEALED = """
                "options": [
                  { "id": "0199f2a4-8d11-7c3e-b0a4-5e29c7d6f813", "text": "Java", "description": null, "displayOrder": 0, "createdAt": "2026-10-09T15:20:31+08:00", "votes": null },
                  { "id": "0199f2a4-8d12-7f60-9b17-2a4c8e0d5f39", "text": "Kotlin", "description": null, "displayOrder": 1, "createdAt": "2026-10-09T15:20:31+08:00", "votes": null }
                ],
            """;
    private static final String POLL_OPTIONS_ENDED = """
                "options": [
                  { "id": "0199f2a4-8d11-7c3e-b0a4-5e29c7d6f813", "text": "Java", "description": null, "displayOrder": 0, "createdAt": "2026-10-09T15:20:31+08:00", "votes": 27 },
                  { "id": "0199f2a4-8d12-7f60-9b17-2a4c8e0d5f39", "text": "Kotlin", "description": null, "displayOrder": 1, "createdAt": "2026-10-09T15:20:31+08:00", "votes": 15 }
                ],
            """;
    private static final String POLL_2 = """
                "tags": [ { "id": "0199f2a4-6c3e-7a1b-9d52-3f8e1b7c4a10", "name": "Tech", "slug": "tech", "description": null, "displayOrder": 1, "usageCount": 18 } ],
                "commentCount": 7,
                "voterVisibility": "OWNER",
                "unread": null,
            """;
    private static final String POLL_MY_OPTION = "    \"myOptionId\": \"0199f2a4-8d11-7c3e-b0a4-5e29c7d6f813\"\n} , \"message\": null, \"errorCode\": null, \"error\": null }";
    private static final String POLL_NO_OPTION = "    \"myOptionId\": null\n} , \"message\": null, \"errorCode\": null, \"error\": null }";

    /** Open Poll, results Sealed, caller chose Java. */
    private static final String EX_POLL_OPEN = POLL_1 + POLL_COUNTS_OPEN + POLL_OPTIONS_SEALED + POLL_2 + POLL_MY_OPTION;
    /** Open Poll after a Retraction: Turnout drops, no Ballot. */
    private static final String EX_POLL_OPEN_NO_BALLOT = POLL_1 + POLL_COUNTS_OPEN + POLL_OPTIONS_SEALED + POLL_2 + POLL_NO_OPTION;
    /** Scheduled Poll (just created): no Participants, no Ballot. */
    private static final String EX_POLL_SCHEDULED = POLL_1 + POLL_COUNTS_NONE + POLL_OPTIONS_SEALED + POLL_2 + POLL_NO_OPTION;
    /** Ended Poll: counts are visible. */
    private static final String EX_POLL_ENDED = POLL_1 + POLL_COUNTS_ENDED + POLL_OPTIONS_ENDED + POLL_2 + POLL_MY_OPTION;
    public static final String EX_PAGE_OF_POLLS = "{ \"success\": true, \"data\": { \"items\": [ { \"id\": \"0199f2a3-5b7e-7d40-a1c8-9e3b2f6d4c05\", "
            + "\"title\": \"Which language should we use for the next side project?\", \"creatorUsername\": \"alice\", "
            + "\"startTime\": \"2026-10-12T09:00:00+08:00\", \"endTime\": \"2026-10-19T09:00:00+08:00\", \"votingActive\": true, "
            + "\"participantCount\": 42, \"commentCount\": 7, \"voterVisibility\": \"OWNER\", \"myOptionId\": null, "
            + "\"options\": [ { \"text\": \"Java\", \"votes\": null }, { \"text\": \"Kotlin\", \"votes\": null } ] } ], "
            + "\"nextCursor\": \"MDE5OWYyYTMtNWI3ZS03ZDQwLWExYzgtOWUzYjJmNmQ0YzA1fDIwMjYtMTAtMTlUMDE6MDA6MDBa\" }, "
            + "\"message\": null, \"errorCode\": null, \"error\": null }";


    @PostMapping
    @Auditable(action = "CREATE", resourceType = "VOTE")
    @CommonApiResponses
    @Operation(summary = "Create a Poll", description = """
            **Auth**: required
            **Precondition**: none
            **Behavior**: creates a Poll owned by the caller. With no `startTime` (or a past one) the Poll is Open immediately; \
            a future `startTime` makes it Scheduled until that time. `voterVisibility` defaults to OWNER.
            **Side effects**: usage count of each attached tag +1; the creation is audit-logged.
            **Errors**:
                    - 400 `vote.multiple.not_allowed`: `allowMultipleChoices` is true
                    - 400 `vote.options.min`: fewer than 2 options
                    - 400 `vote.options.max`: more than 10 options
                    - 400 `vote.end_time.past`: `endTime` is in the past
                    - 400 `vote.end_time.before_start`: `endTime` is not after `startTime`
                    - 400 `vote.duration.exceeded`: `endTime` is more than 365 days after the start time
                    - 400 `tag.ids.invalid`: one of `tagIds` does not exist
                    - 400 `common.validation_failed`: a field breaks its size / required rule (first failing field only)
            - 404 `user.not_found`: the signed-in user no longer exists
            """,
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    examples = @ExampleObject(name = "Scheduled Poll", value = """
                            {
                              "title": "Which language should we use for the next side project?",
                              "description": "Vote before Friday.",
                              "options": [ { "text": "Java" }, { "text": "Kotlin", "description": "Coroutines" } ],
                              "startTime": "2026-10-12T09:00:00+08:00",
                              "endTime": "2026-10-19T09:00:00+08:00",
                              "voterVisibility": "OWNER",
                              "tagIds": [ "0199f2a4-6c3e-7a1b-9d52-3f8e1b7c4a10" ]
                            }
                            """))))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Poll created",
            content = @Content(examples = @ExampleObject(name = "Scheduled Poll", value = EX_POLL_SCHEDULED)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "The signed-in user no longer exists (errorCode `user.not_found`)",
            content = @Content(schema = @Schema(ref = ERR),
                    examples = @ExampleObject(name = "user.not_found", value = ERR_1 + "User not found" + ERR_2 + "user.not_found" + ERR_3)))
    public ResponseEntity<ApiResponse<VoteResponse>> createVote(
            @Valid @RequestBody CreateVoteRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        VoteResponse voteResponse = voteService.createVote(request, principal.userId());
        log.info("Vote created: {} by user: {}", voteResponse.getId(), principal.userId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(voteResponse));
    }

    @PutMapping("/{voteId}")
    @Auditable(action = "UPDATE", resourceType = "VOTE", resourceIdIndex = 0)
    @CommonApiResponses
    @Operation(summary = "Edit a Scheduled Poll", description = """
            **Auth**: required (owner only)
            **Precondition**: the Poll is Scheduled (its start time has not arrived and it has not been Closed)
            **Behavior**: replaces title, description, times, Voter Visibility, tags and options with the body (same body and validation as create). \
            An omitted `startTime` keeps the scheduled one; the Poll does not open on edit.
            **Side effects**: all options are replaced, so option IDs change; tag usage counts are adjusted for added / removed tags.
            **Errors**:
            - 403 `vote.forbidden`: the caller is not the owner
            - 404 `vote.not_found`: Poll does not exist
            - 400 `vote.not_editable`: the Poll is Open or Ended
            - 400 `vote.multiple.not_allowed`: `allowMultipleChoices` is true
            - 400 `vote.options.min`: fewer than 2 options
            - 400 `vote.options.max`: more than 10 options
            - 400 `vote.end_time.past`: `endTime` is in the past
            - 400 `vote.end_time.before_start`: `endTime` is not after `startTime`
            - 400 `vote.duration.exceeded`: `endTime` is more than 365 days after the start time
            - 400 `tag.ids.invalid`: one of `tagIds` does not exist
            - 400 `common.validation_failed`: a field breaks its size / required rule (first failing field only)
            """,
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    examples = @ExampleObject(name = "Reschedule", value = """
                            {
                              "title": "Which language should we use for the next side project?",
                              "options": [ { "text": "Java" }, { "text": "Kotlin" }, { "text": "Go" } ],
                              "endTime": "2026-10-20T09:00:00+08:00"
                            }
                            """))))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Poll updated",
            content = @Content(examples = @ExampleObject(name = "Scheduled Poll", value = EX_POLL_SCHEDULED)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Poll is not Scheduled (`vote.not_editable`) or the body breaks a rule (`vote.options.min`, `vote.end_time.past`, `tag.ids.invalid`, ...)",
            content = @Content(schema = @Schema(ref = ERR),
                    examples = @ExampleObject(name = "vote.not_editable", value = ERR_1 + "Poll can no longer be edited" + ERR_2 + "vote.not_editable" + ERR_3)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not the owner (`vote.forbidden`)",
            content = @Content(schema = @Schema(ref = ERR),
                    examples = @ExampleObject(name = "vote.forbidden", value = ERR_1 + "Not allowed to modify this Poll" + ERR_2 + "vote.forbidden" + ERR_3)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Poll does not exist (`vote.not_found`)",
            content = @Content(schema = @Schema(ref = ERR),
                    examples = @ExampleObject(name = "vote.not_found", value = ERR_1 + "Poll not found" + ERR_2 + "vote.not_found" + ERR_3)))
    public ResponseEntity<ApiResponse<VoteResponse>> updateVote(
            @Parameter(description = "Poll ID", required = true, example = "0199f2a3-5b7e-7d40-a1c8-9e3b2f6d4c05")
            @PathVariable String voteId,
            @Valid @RequestBody CreateVoteRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(voteService.updateVote(voteId, request, principal.userId())));
    }

    @GetMapping("/{voteId}")
    @PublicApiResponse
    @Operation(summary = "Get Poll by ID", description = """
            **Auth**: public (optional login changes response: `myOptionId` holds the caller's Selection when signed in and a Ballot exists, otherwise null)
            **Precondition**: none; Scheduled, Open and Ended Polls are all readable
            **Behavior**: returns the Poll. `options[].votes` is null (Sealed) for everyone, owner included, until the Poll has Ended; `participantCount` (Turnout) is always present.
            **Side effects**: none
            **Errors**:
            - 404 `vote.not_found`: Poll does not exist
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Poll (shown: Open, results Sealed, caller chose Java)",
            content = @Content(examples = {
                    @ExampleObject(name = "Open Poll (Sealed)", value = EX_POLL_OPEN),
                    @ExampleObject(name = "Ended Poll", value = EX_POLL_ENDED) }))
    public ResponseEntity<ApiResponse<VoteResponse>> getVote(
            @Parameter(description = "Poll ID", required = true, example = "0199f2a3-5b7e-7d40-a1c8-9e3b2f6d4c05")
            @PathVariable String voteId,
            @AuthenticationPrincipal UserPrincipal principal) {
        VoteResponse response = voteService.getVote(voteId, principal != null ? principal.userId() : null);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping
    @PublicApiResponse
    @Operation(summary = "List Polls (Feed / Explore / Search)", description = """
            **Auth**: public (optional login changes response: `myOptionId` of each item is filled for the caller's Ballots)
            **Precondition**: none
            **Behavior**: cursor-paged list of Open or Ended Polls; Scheduled Polls never appear.
            - `status=open` (default) + `sort=newest` (default): most recently opened first (the Feed)
            - `status=open` + `sort=closing`: closing soonest first
            - `status=ended`: most recently ended first; `sort` must be omitted
            - `tag`: only Polls under that tag slug (Explore); `q`: free-text match on title and description, option text is not matched (Search), at least 2 characters
            - Filters combine. `nextCursor` null means the last page; pass it back unchanged as `cursor`. `limit` is clamped to 1-50 (default 20).
            **Side effects**: none
            **Errors**:
            - 400 `common.invalid_status`: `status` is neither `open` nor `ended`
            - 400 `common.invalid_sort`: `sort` is neither `newest` nor `closing`
            - 400 `vote.list.sort_not_allowed`: `sort` was sent together with `status=ended`
            - 400 `vote.search.query_too_short`: `q` is shorter than 2 characters
            - 400 `common.cursor_invalid`: `cursor` was not issued by this endpoint
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Page of Polls",
            content = @Content(examples = @ExampleObject(name = "Page", value = EX_PAGE_OF_POLLS)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid filter or cursor (`common.invalid_status`, `common.invalid_sort`, `vote.list.sort_not_allowed`, `vote.search.query_too_short`, `common.cursor_invalid`)",
            content = @Content(schema = @Schema(ref = ERR),
                    examples = @ExampleObject(name = "vote.search.query_too_short", value = ERR_1 + "Search query must be at least 2 characters" + ERR_2 + "vote.search.query_too_short" + ERR_3)))
    public ResponseEntity<ApiResponse<CursorResponse<VoteResponse>>> listVotes(
            @Parameter(description = "`open` (default) or `ended`", example = "open") @RequestParam(required = false) String status,
            @Parameter(description = "`newest` (default) or `closing`; only valid with `status=open`", example = "closing") @RequestParam(required = false) String sort,
            @Parameter(description = "Tag slug to browse (Explore); omitted or blank means no tag filter", example = "tech") @RequestParam(required = false) String tag,
            @Parameter(description = "Free-text query on title and description (Search); at least 2 characters", example = "side project") @RequestParam(required = false) String q,
            @Parameter(description = "`nextCursor` of the previous page; omit for the first page") @RequestParam(required = false) String cursor,
            @Parameter(description = "Page size, 1-50 (default 20); out-of-range values are clamped", example = "20") @RequestParam(required = false) Integer limit,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(voteService.listVotes(VoteListOrder.of(status, sort), tag, q, cursor,
                limit, principal != null ? principal.userId() : null)));
    }

    @GetMapping("/my")
    @CommonApiResponses
    @Operation(summary = "My Polls", description = """
            **Auth**: required
            **Precondition**: none
            **Behavior**: cursor-paged Polls the caller created or holds a Ballot in.
            - `status=open` (default): the caller's Scheduled and Open Polls, closing soonest first
            - `status=ended`: Ended Polls (including Cancelled ones), most recently ended first; each item carries `unread` (Ended Notification not yet read; false for a Cancelled Poll)
            - `nextCursor` null means the last page; `limit` is clamped to 1-50 (default 20).
            **Side effects**: none
            **Errors**:
            - 400 `common.invalid_status`: `status` is neither `open` nor `ended`
            - 400 `common.cursor_invalid`: `cursor` was not issued by this endpoint
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Page of the caller's Polls",
            content = @Content(examples = @ExampleObject(name = "Page", value = EX_PAGE_OF_POLLS)))
    public ResponseEntity<ApiResponse<CursorResponse<VoteResponse>>> getMyVotes(
            @Parameter(description = "`open` (default, includes the caller's Scheduled Polls) or `ended`", example = "open") @RequestParam(required = false) String status,
            @Parameter(description = "`nextCursor` of the previous page; omit for the first page") @RequestParam(required = false) String cursor,
            @Parameter(description = "Page size, 1-50 (default 20); out-of-range values are clamped", example = "20") @RequestParam(required = false) Integer limit,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(voteService.getMyPolls(principal.userId(), status, cursor, limit)));
    }

    @PostMapping("/{voteId}/vote")
    @Auditable(action = "VOTE", resourceType = "VOTE", resourceIdIndex = 0)
    @CommonApiResponses
    @Operation(summary = "Cast a Ballot", description = """
            **Auth**: required
            **Precondition**: the Poll is Open (not Scheduled, not Ended)
            **Behavior**: records the caller's Ballot with exactly one Selection. If the caller already holds a Ballot it is replaced by the new one (change of mind); \
            casting the same option again changes nothing. The response is the Poll with the results still Sealed and `myOptionId` set.
            **Side effects**: Turnout +1 on the first Ballot only; the option counts move silently (not visible until the Poll has Ended); the action is audit-logged.
            **Errors**:
            - 404 `vote.not_found`: Poll does not exist
            - 400 `vote.ended`: the Poll has Ended; Ballots are frozen
            - 400 `vote.not_allowed`: the Poll is still Scheduled
            - 400 `vote.multiple.not_allowed`: `optionIds` holds more than one ID
            - 404 `vote.option.not_found`: the option ID does not exist
            - 400 `vote.option.not_in_vote`: the option belongs to another Poll
            - 404 `user.not_found`: the signed-in user no longer exists
            - 400 `common.validation_failed`: `optionIds` is empty
            """,
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    examples = @ExampleObject(name = "Choose Java", value = """
                            { "optionIds": [ "0199f2a4-8d11-7c3e-b0a4-5e29c7d6f813" ] }
                            """))))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Ballot recorded; Poll with results Sealed",
            content = @Content(examples = @ExampleObject(name = "Open Poll (Sealed)", value = EX_POLL_OPEN)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Poll has Ended (`vote.ended`), is still Scheduled (`vote.not_allowed`), or the Selection is invalid (`vote.multiple.not_allowed`, `vote.option.not_in_vote`)",
            content = @Content(schema = @Schema(ref = ERR),
                    examples = @ExampleObject(name = "vote.ended", value = ERR_1 + "Voting for this Poll has ended" + ERR_2 + "vote.ended" + ERR_3)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Poll or option does not exist (`vote.not_found`, `vote.option.not_found`, `user.not_found`)",
            content = @Content(schema = @Schema(ref = ERR),
                    examples = @ExampleObject(name = "vote.not_found", value = ERR_1 + "Poll not found" + ERR_2 + "vote.not_found" + ERR_3)))
    public ResponseEntity<ApiResponse<VoteResponse>> vote(
            @Parameter(description = "Poll ID", required = true, example = "0199f2a3-5b7e-7d40-a1c8-9e3b2f6d4c05")
            @PathVariable String voteId,
            @Valid @RequestBody VoteRequest request,
            @AuthenticationPrincipal UserPrincipal principal,
            HttpServletRequest httpRequest) {
        String ipAddress = getClientIpAddress(httpRequest);
        VoteResponse response = voteService.vote(voteId, request, principal.userId(), ipAddress);
        log.info("User {} voted on vote {}", principal.userId(), voteId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @DeleteMapping("/{voteId}/vote")
    @Auditable(action = "RETRACT", resourceType = "VOTE", resourceIdIndex = 0)
    @CommonApiResponses
    @Operation(summary = "Retract a Ballot", description = """
            **Auth**: required
            **Precondition**: the Poll is Open (not Scheduled, not Ended)
            **Behavior**: withdraws the caller's whole Ballot (Retraction); the caller is no longer a Participant. Idempotent: succeeds, changing nothing, when the caller holds no Ballot.
            **Side effects**: Turnout -1 when a Ballot was removed. A Participant who Retracted gets no Ended Notification.
            **Errors**:
            - 404 `vote.not_found`: Poll does not exist
            - 400 `vote.ended`: the Poll has Ended; Ballots are frozen
            - 400 `vote.not_allowed`: the Poll is still Scheduled
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Ballot withdrawn (or none existed); `myOptionId` is null",
            content = @Content(examples = @ExampleObject(name = "Open Poll (Sealed)", value = EX_POLL_OPEN_NO_BALLOT)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Poll has Ended (`vote.ended`) or is still Scheduled (`vote.not_allowed`)",
            content = @Content(schema = @Schema(ref = ERR),
                    examples = @ExampleObject(name = "vote.ended", value = ERR_1 + "Voting for this Poll has ended" + ERR_2 + "vote.ended" + ERR_3)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Poll does not exist (`vote.not_found`)",
            content = @Content(schema = @Schema(ref = ERR),
                    examples = @ExampleObject(name = "vote.not_found", value = ERR_1 + "Poll not found" + ERR_2 + "vote.not_found" + ERR_3)))
    public ResponseEntity<ApiResponse<VoteResponse>> retract(
            @Parameter(description = "Poll ID", required = true, example = "0199f2a3-5b7e-7d40-a1c8-9e3b2f6d4c05")
            @PathVariable String voteId,
            @AuthenticationPrincipal UserPrincipal principal) {
        VoteResponse response = voteService.retract(voteId, principal.userId());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @DeleteMapping("/{voteId}/vote/{optionId}")
    @CommonApiResponses
    @Operation(summary = "Retract a Ballot by option", description = """
            **Auth**: required
            **Precondition**: the Poll is Open (not Scheduled, not Ended)
            **Behavior**: kept for compatibility; withdraws the caller's Ballot only if it chose `optionId` (same effect as a Retraction then). \
            Succeeds without change when the caller's Ballot chose another option or none exists. Prefer `DELETE /api/votes/{voteId}/vote`.
            **Side effects**: Turnout -1 when a Ballot was removed.
            **Errors**:
            - 404 `vote.not_found`: Poll does not exist
            - 400 `vote.ended`: the Poll has Ended; Ballots are frozen
            - 400 `vote.not_allowed`: the Poll is still Scheduled
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Poll after the removal",
            content = @Content(examples = @ExampleObject(name = "Open Poll (Sealed)", value = EX_POLL_OPEN)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Poll has Ended (`vote.ended`) or is still Scheduled (`vote.not_allowed`)",
            content = @Content(schema = @Schema(ref = ERR),
                    examples = @ExampleObject(name = "vote.ended", value = ERR_1 + "Voting for this Poll has ended" + ERR_2 + "vote.ended" + ERR_3)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Poll does not exist (`vote.not_found`)",
            content = @Content(schema = @Schema(ref = ERR),
                    examples = @ExampleObject(name = "vote.not_found", value = ERR_1 + "Poll not found" + ERR_2 + "vote.not_found" + ERR_3)))
    public ResponseEntity<ApiResponse<VoteResponse>> removeVote(
            @Parameter(description = "Poll ID", required = true, example = "0199f2a3-5b7e-7d40-a1c8-9e3b2f6d4c05")
            @PathVariable String voteId,
            @Parameter(description = "Option ID", required = true, example = "0199f2a4-8d11-7c3e-b0a4-5e29c7d6f813")
            @PathVariable String optionId,
            @AuthenticationPrincipal UserPrincipal principal) {
        VoteResponse response = voteService.removeVote(voteId, optionId, principal.userId());
        log.info("User {} removed vote from option {} in vote {}",
                principal.userId(), optionId, voteId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping("/{voteId}/results")
    @PublicApiResponse
    @Operation(summary = "Get Poll results", description = """
            **Auth**: public
            **Precondition**: the Poll has Ended (Sealed before that, for everyone including the owner)
            **Behavior**: returns per-option counts and Support (`percentage`, 0-100, share of Participants) plus Turnout.
            **Side effects**: none
            **Errors**:
            - 404 `vote.not_found`: Poll does not exist
            - 403 `vote.results.sealed`: the Poll is still Scheduled or Open
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Results of the Ended Poll",
            content = @Content(examples = @ExampleObject(name = "Ended Poll results", value = """
                    { "success": true, "data": {
                        "id": "0199f2a3-5b7e-7d40-a1c8-9e3b2f6d4c05",
                        "title": "Which language should we use for the next side project?",
                        "description": "Vote before Friday.",
                        "creatorId": "0199f2a1-1b4c-7e02-8a9d-6c3f0d2e5b71",
                        "creatorUsername": "alice",
                        "startTime": "2026-10-12T09:00:00+08:00",
                        "endTime": "2026-10-19T09:00:00+08:00",
                        "active": true, "allowMultipleChoices": false, "anonymous": false,
                        "createdAt": "2026-10-09T15:20:31+08:00",
                        "totalVotes": 42, "totalParticipants": 42, "votingActive": false,
                        "options": [
                          { "id": "0199f2a4-8d11-7c3e-b0a4-5e29c7d6f813", "text": "Java", "description": null, "displayOrder": 0, "voteCount": 27, "percentage": 64.28571428571429 },
                          { "id": "0199f2a4-8d12-7f60-9b17-2a4c8e0d5f39", "text": "Kotlin", "description": null, "displayOrder": 1, "voteCount": 15, "percentage": 35.714285714285715 }
                        ]
                      }, "message": null, "errorCode": null, "error": null }
                    """)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "The Poll has not Ended, results are Sealed (`vote.results.sealed`)",
            content = @Content(schema = @Schema(ref = ERR),
                    examples = @ExampleObject(name = "vote.results.sealed", value = ERR_1 + "Results are sealed until the Poll ends" + ERR_2 + "vote.results.sealed" + ERR_3)))
    public ResponseEntity<ApiResponse<VoteResultResponse>> getVoteResults(
            @Parameter(description = "Poll ID", required = true, example = "0199f2a3-5b7e-7d40-a1c8-9e3b2f6d4c05")
            @PathVariable String voteId) {
        VoteResultResponse response = voteService.getVoteResults(voteId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping("/{voteId}/voters")
    @CommonApiResponses
    @Operation(summary = "List Participants of an Ended Poll", description = """
            **Auth**: required
            **Precondition**: the Poll has Ended, and its Voter Visibility allows the caller: NOBODY allows no one (not even the owner), OWNER allows only the owner, SIGNED_IN allows any signed-in user
            **Behavior**: cursor-paged list of who chose which option, oldest Ballot first. `nextCursor` null means the last page; `limit` is clamped to 1-50 (default 20).
            **Side effects**: none
            **Errors**:
            - 404 `vote.not_found`: Poll does not exist
            - 403 `vote.results.sealed`: the Poll is still Scheduled or Open
            - 403 `vote.voters.hidden`: Voter Visibility does not allow the caller
            - 400 `common.cursor_invalid`: `cursor` was not issued by this endpoint
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Page of Participants",
            content = @Content(examples = @ExampleObject(name = "Page", value = """
                    { "success": true, "data": {
                        "items": [
                          { "userId": "0199f2a1-1b4c-7e02-8a9d-6c3f0d2e5b71", "username": "alice", "optionId": "0199f2a4-8d11-7c3e-b0a4-5e29c7d6f813", "votedAt": "2026-10-15T20:41:07+08:00" }
                        ],
                        "nextCursor": null
                      }, "message": null, "errorCode": null, "error": null }
                    """)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Poll not Ended (`vote.results.sealed`) or Voter Visibility does not allow the caller (`vote.voters.hidden`)",
            content = @Content(schema = @Schema(ref = ERR),
                    examples = @ExampleObject(name = "vote.voters.hidden", value = ERR_1 + "Voters of this Poll are hidden" + ERR_2 + "vote.voters.hidden" + ERR_3)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Poll does not exist (`vote.not_found`)",
            content = @Content(schema = @Schema(ref = ERR),
                    examples = @ExampleObject(name = "vote.not_found", value = ERR_1 + "Poll not found" + ERR_2 + "vote.not_found" + ERR_3)))
    public ResponseEntity<ApiResponse<CursorResponse<VoterResponse>>> getVoters(
            @Parameter(description = "Poll ID", required = true, example = "0199f2a3-5b7e-7d40-a1c8-9e3b2f6d4c05")
            @PathVariable String voteId,
            @Parameter(description = "`nextCursor` of the previous page; omit for the first page") @RequestParam(required = false) String cursor,
            @Parameter(description = "Page size, 1-50 (default 20); out-of-range values are clamped", example = "20") @RequestParam(required = false) Integer limit,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(voteService.getVoters(voteId, principal.userId(), cursor, limit)));
    }

    @GetMapping("/{voteId}/my-vote-status")
    @CommonApiResponses
    @Operation(summary = "Get my Ballot status", description = """
            **Auth**: required
            **Precondition**: none
            **Behavior**: tells whether the caller holds a Ballot in the Poll and which option it chose. Works in any Poll state. An unknown Poll ID is not an error: `hasVoted` is false.
            **Side effects**: none
            **Errors**: none specific to this endpoint (only the common 400 / 401 / 429)
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Ballot status",
            content = @Content(examples = {
                    @ExampleObject(name = "Has a Ballot", value = """
                            { "success": true, "data": { "hasVoted": true, "selectedOptions": [ "0199f2a4-8d11-7c3e-b0a4-5e29c7d6f813" ] },
                              "message": null, "errorCode": null, "error": null }
                            """),
                    @ExampleObject(name = "No Ballot", value = """
                            { "success": true, "data": { "hasVoted": false, "selectedOptions": [] },
                              "message": null, "errorCode": null, "error": null }
                            """) }))
    public ResponseEntity<ApiResponse<UserVoteStatusResponse>> getMyVoteStatus(
            @Parameter(description = "Poll ID", required = true, example = "0199f2a3-5b7e-7d40-a1c8-9e3b2f6d4c05")
            @PathVariable String voteId,
            @AuthenticationPrincipal UserPrincipal principal) {
        UserVoteStatusResponse response = new UserVoteStatusResponse();
        response.setHasVoted(voteService.hasUserVoted(voteId, principal.userId()));
        response.setSelectedOptions(voteService.getUserVoteOptions(voteId, principal.userId()));
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PutMapping("/{voteId}/deactivate")
    @Auditable(action = "DEACTIVATE", resourceType = "VOTE", resourceIdIndex = 0)
    @CommonApiResponses
    @Operation(summary = "Close a Poll", description = """
            **Auth**: required (owner only)
            **Precondition**: none; works in any Poll state
            **Behavior**: Open Poll: ends now (Ended). Scheduled Poll: cancelled, i.e. Ended without ever accepting Ballots (`endTime` becomes earlier than `startTime`). \
            Ended Poll: nothing changes. Fetch the Poll again to see the new state.
            **Side effects**: the Poll becomes Ended, so results and Voter Visibility open up; the owner and Participants receive an Ended Notification, except for a Poll Closed while Scheduled.
            **Errors**:
            - 403 `vote.forbidden`: the caller is not the owner
            - 404 `vote.not_found`: Poll does not exist
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Poll closed (or already Ended)",
            content = @Content(examples = @ExampleObject(name = "Closed", value = """
                    { "success": true, "data": { "success": true, "id": null, "status": null, "message": null },
                      "message": null, "errorCode": null, "error": null }
                    """)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not the owner (`vote.forbidden`)",
            content = @Content(schema = @Schema(ref = ERR),
                    examples = @ExampleObject(name = "vote.forbidden", value = ERR_1 + "Not allowed to modify this Poll" + ERR_2 + "vote.forbidden" + ERR_3)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Poll does not exist (`vote.not_found`)",
            content = @Content(schema = @Schema(ref = ERR),
                    examples = @ExampleObject(name = "vote.not_found", value = ERR_1 + "Poll not found" + ERR_2 + "vote.not_found" + ERR_3)))
    public ResponseEntity<ApiResponse<SimpleResultResponse>> deactivateVote(
            @Parameter(description = "Poll ID", required = true, example = "0199f2a3-5b7e-7d40-a1c8-9e3b2f6d4c05")
            @PathVariable String voteId,
            @AuthenticationPrincipal UserPrincipal principal) {
        voteService.deactivateVote(voteId, principal.userId());
        log.info("Vote {} deactivated by creator {}", voteId, principal.userId());
        return ResponseEntity.ok(ApiResponse.ok(SimpleResultResponse.ok()));
    }

    private String getClientIpAddress(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty()) {
            return xRealIp;
        }
        return request.getRemoteAddr();
    }
}
