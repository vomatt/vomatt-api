package com.vomatt.votes;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import java.util.List;
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
import com.vomatt.votes.dto.InviteeResponse;
import com.vomatt.votes.dto.InviteesRequest;
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

import static com.vomatt.common.config.OpenAPIConfig.ERR_CODE;
import static com.vomatt.common.config.OpenAPIConfig.ERR_END;
import static com.vomatt.common.config.OpenAPIConfig.ERR_ERROR;
import static com.vomatt.common.config.OpenAPIConfig.ERR_MESSAGE;
import static com.vomatt.common.config.OpenAPIConfig.EX_PAGE_OF_POLLS;

@RestController
@RequestMapping("/api/votes")
@RequiredArgsConstructor
@Tag(name = "Vote", description = "Poll APIs: create, edit, Close, cast / retract a Ballot, results, Participants, Feed / Explore / Search listing")
public class VoteController {
    private static final Logger log = LoggerFactory.getLogger(VoteController.class);
    private final VoteService voteService;

    private static final String POLL_1 = """
            { "success": true, "data": {
                "id": "0199f2a3-5b7e-7d40-a1c8-9e3b2f6d4c05",
                "title": "Which language should we use for the next side project?",
                "description": "Vote before Friday.",
                "creatorId": "0199f2a1-1b4c-7e02-8a9d-6c3f0d2e5b71",
                "creatorUsername": "alice",
                "startTime": "2026-10-12T09:00:00+08:00",
                "endTime": "2026-10-19T09:00:00+08:00",
                "deadline": "2026-10-19T09:00:00+08:00",
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
                "audience": "PUBLIC",
                "inviteeCount": null,
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


    @PostMapping
    @Auditable(action = "CREATE", resourceType = "VOTE")
    @CommonApiResponses
    @Operation(summary = "Create a Poll", description = """
            **Auth**: required
            **Precondition**: none
            **Behavior**: creates a Poll owned by the caller. With no `startTime` (or a past one) the Poll is Open immediately; \
            a future `startTime` makes it Scheduled until that time. `voterVisibility` defaults to OWNER; `audience` defaults to PUBLIC. \
            An UNLISTED Poll is reachable only through its link: it stays out of Feed / Explore / Search and the owner's profile, \
            and is listed only in My Polls. An INVITE_ONLY Poll is reachable only by its owner and its Invitees: the users in `inviteeIds` and the addresses in `inviteeEmails` \
            (duplicates and the owner's own ID / address are ignored; more can be added later); everyone else gets 404 `vote.not_found`. \
            An address is always listed as entered, whether or not it belongs to an account; an active account's user gets access right away, any other address once someone verifies it at sign-up / sign-in. \
            `groupIds` invites the current members of the caller's Groups: members added by ID join `inviteeIds` (suspended ones are skipped) and members added by email \
            join `inviteeEmails` by their stored address; later Group changes never touch the Poll.
            **Side effects**: usage count of each attached tag +1 for a PUBLIC Poll (UNLISTED and INVITE_ONLY Polls are not counted); \
            each Invitee tied to a user gets an unread Invitation notification (unread count, `unread` on My Polls `status=open`); each new email Invitee \
            is sent an invitation email linking to the Poll (only when email is enabled) \
            (at most once per address per Poll, even after removing and re-adding it, and at most `EMAIL_INVITATION_DAILY_LIMIT` (default 200) per owner per day; \
            over the limit the Invitee is still added and only the email is skipped) and gets the Invitation notification once tied to a user; the creation is audit-logged.
            **Errors**:
                    - 400 `vote.invitee.not_invite_only`: `inviteeIds`, `inviteeEmails` or `groupIds` is not empty but `audience` is not INVITE_ONLY
                    - 400 `vote.invitee.ids_invalid`: one of `inviteeIds` is not an existing user or is suspended (`inviteeEmails` and `groupIds` never cause it)
                    - 400 `vote.multiple.not_allowed`: `allowMultipleChoices` is true
                    - 400 `vote.options.min`: fewer than 2 options
                    - 400 `vote.options.max`: more than 10 options
                    - 400 `vote.end_time.past`: `endTime` is in the past
                    - 400 `vote.end_time.before_start`: `endTime` is not after `startTime`
                    - 400 `vote.duration.exceeded`: `endTime` is more than 365 days after the start time
                    - 400 `tag.ids.invalid`: one of `tagIds` does not exist
                    - 400 `common.validation_failed`: a field breaks its size / required rule (first failing field only)
            - 404 `user.not_found`: the signed-in user no longer exists
            - 404 `group.not_found`: one of `groupIds` does not exist or belongs to someone else
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
                              "audience": "PUBLIC",
                              "tagIds": [ "0199f2a4-6c3e-7a1b-9d52-3f8e1b7c4a10" ]
                            }
                            """))))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Poll created",
            content = @Content(examples = @ExampleObject(name = "Scheduled Poll", value = EX_POLL_SCHEDULED)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "The signed-in user no longer exists (errorCode `user.not_found`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "user.not_found", value = ERR_MESSAGE + "User not found" + ERR_CODE + "user.not_found" + ERR_ERROR + "User not found" + ERR_END)))
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
            **Precondition**: the Poll is Scheduled (its start time has not arrived)
            **Behavior**: replaces title, description, times, Voter Visibility, Audience, Invitees, tags and options with the body (same body and validation as create). \
            An omitted `startTime`, `voterVisibility`, `audience`, `inviteeIds` or `inviteeEmails` keeps the current value; the Poll does not open on edit. \
            Audience can change only here, so it is fixed once the Poll is Open. `groupIds` expands the caller's Groups now: their members are merged into \
            `inviteeIds` / `inviteeEmails` when that list is set, so an explicit `[]` plus `groupIds` replaces that kind of Invitee with the Groups' members \
            (already-invited members are kept as they are); a null list keeps that kind of Invitee and adds the Groups' members on top.
            **Side effects**: all options are replaced, so option IDs change; tag usage counts are adjusted for added / removed tags \
            and for an Audience change (only PUBLIC Polls are counted). `inviteeIds` replaces the Invitees added by ID and `inviteeEmails` those added by email (matched by address); \
            an Audience other than INVITE_ONLY removes them all. Each new Invitee tied to a user gets an unread Invitation notification, including one removed and invited again; \
            each newly added email Invitee is sent an invitation email (at most once per address per Poll, even after removing and re-adding it, and at most `EMAIL_INVITATION_DAILY_LIMIT` (default 200) per owner per day; \
            over the limit the Invitee is still added and only the email is skipped).
            **Errors**:
            - 403 `vote.forbidden`: the caller is not the owner
            - 404 `vote.not_found`: Poll does not exist, is Deleted, or is INVITE_ONLY and the caller is neither its owner nor an Invitee
            - 400 `vote.not_editable`: the Poll is Open or Ended
            - 400 `vote.invitee.not_invite_only`: `inviteeIds`, `inviteeEmails` or `groupIds` is not empty but the resulting Audience is not INVITE_ONLY
            - 400 `vote.invitee.ids_invalid`: one of `inviteeIds` is not an existing user or is suspended (`inviteeEmails` and `groupIds` never cause it)
            - 400 `vote.multiple.not_allowed`: `allowMultipleChoices` is true
            - 400 `vote.options.min`: fewer than 2 options
            - 400 `vote.options.max`: more than 10 options
            - 400 `vote.end_time.past`: `endTime` is in the past
            - 400 `vote.end_time.before_start`: `endTime` is not after `startTime`
            - 400 `vote.duration.exceeded`: `endTime` is more than 365 days after the start time
            - 400 `tag.ids.invalid`: one of `tagIds` does not exist
            - 400 `common.validation_failed`: a field breaks its size / required rule (first failing field only)
            - 404 `group.not_found`: one of `groupIds` does not exist or belongs to someone else
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
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "vote.not_editable", value = ERR_MESSAGE + "Poll can no longer be edited" + ERR_CODE + "vote.not_editable" + ERR_ERROR + "Poll can no longer be edited" + ERR_END)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not the owner (`vote.forbidden`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "vote.forbidden", value = ERR_MESSAGE + "Not allowed to modify this Poll" + ERR_CODE + "vote.forbidden" + ERR_ERROR + "Not allowed to modify this Poll" + ERR_END)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Poll does not exist, is Deleted, or is Invite-only and hidden from the caller (`vote.not_found`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "vote.not_found", value = ERR_MESSAGE + "Poll not found" + ERR_CODE + "vote.not_found" + ERR_ERROR + "Poll not found" + ERR_END)))
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
            **Behavior**: returns the Poll. `options[].votes` is null (Sealed) for everyone, owner included, until the Poll has Ended; `participantCount` (Turnout) is always present, \
            and so is `inviteeCount` for an INVITE_ONLY Poll.
            **Side effects**: none
            **Errors**:
            - 404 `vote.not_found`: Poll does not exist, is Deleted, or is INVITE_ONLY and the caller is neither its owner nor an Invitee
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
    @Operation(summary = "List Polls (Feed / Explore / Search / Top)", description = """
            **Auth**: public (optional login changes response: `myOptionId` of each item is filled for the caller's Ballots)
            **Precondition**: none
            **Behavior**: cursor-paged list of Open or Ended PUBLIC Polls; Scheduled, UNLISTED and INVITE_ONLY Polls never appear.
            - `status=open` (default) + `sort=newest` (default): most recently opened first (the Feed)
            - `status=open` + `sort=closing`: closing soonest first
            - `status=open` + `sort=top`: highest Turnout (`participantCount`) first, ties most recently opened first (Top); Turnout can change between pages, so dedupe items by `id`
            - `status=ended`: most recently ended first; `sort` must be omitted
            - `tag`: only Polls under that tag slug (Explore); `q`: free-text match on title and description, option text is not matched (Search), at least 2 characters
            - Filters combine. `nextCursor` null means the last page; pass it back unchanged as `cursor`. `limit` is clamped to 1-50 (default 20).
            **Side effects**: none
            **Errors**:
            - 400 `common.invalid_status`: `status` is neither `open` nor `ended`
            - 400 `common.invalid_sort`: `sort` is not `newest`, `closing` or `top`
            - 400 `vote.list.sort_not_allowed`: `sort` was sent together with `status=ended`
            - 400 `vote.search.query_too_short`: `q` is shorter than 2 characters
            - 400 `common.cursor_invalid`: `cursor` is malformed or was issued by another list or another ordering of this list
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Page of Polls",
            content = @Content(examples = @ExampleObject(name = "Page", value = EX_PAGE_OF_POLLS)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid filter or cursor (`common.invalid_status`, `common.invalid_sort`, `vote.list.sort_not_allowed`, `vote.search.query_too_short`, `common.cursor_invalid`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "vote.search.query_too_short", value = ERR_MESSAGE + "Search query must be at least 2 characters" + ERR_CODE + "vote.search.query_too_short" + ERR_ERROR + "Search query must be at least 2 characters" + ERR_END)))
    public ResponseEntity<ApiResponse<CursorResponse<VoteResponse>>> listVotes(
            @Parameter(description = "`open` (default) or `ended`", example = "open") @RequestParam(required = false) String status,
            @Parameter(description = "`newest` (default), `closing` or `top`; only valid with `status=open`", example = "closing") @RequestParam(required = false) String sort,
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
            **Behavior**: cursor-paged Polls the caller created, holds a Ballot in or is an Invitee of, of any Audience (UNLISTED and INVITE_ONLY included).
            - `status=open` (default): the caller's Scheduled and Open Polls, closing soonest first; each item carries `unread` (Invitation to an INVITE_ONLY Poll not yet read; false otherwise)
            - `status=ended`: Ended Polls (including Cancelled ones), most recently ended first; each item carries `unread` (Ended Notification not yet read; false for a Cancelled Poll)
            - `nextCursor` null means the last page; `limit` is clamped to 1-50 (default 20).
            **Side effects**: none
            **Errors**:
            - 400 `common.invalid_status`: `status` is neither `open` nor `ended`
            - 400 `common.cursor_invalid`: `cursor` is malformed or was issued by another list or another ordering of this list
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
            casting the same option again only updates `anonymous`. `anonymous: true` makes it an Anonymous Ballot, left out of `GET /api/votes/{voteId}/voters` \
            whatever the Poll's Voter Visibility; an omitted or `null` `anonymous` keeps the current Ballot's flag, and a first Ballot uses the caller's `anonymousBallotByDefault` (false until set). \
            The response is the Poll with the results still Sealed and `myOptionId` set.
            **Side effects**: Turnout +1 on the first Ballot only; the option counts move silently (not visible until the Poll has Ended); the action is audit-logged. \
            When this Ballot leaves every Invitee of an INVITE_ONLY Poll holding a Ballot (the owner's own Ballot never counts), the Poll Ends now \
            (`endTime` moves, `deadline` stays), so the response comes back Ended with results visible and the Ended Notification follows.
            **Errors**:
            - 404 `vote.not_found`: Poll does not exist, is Deleted, or is INVITE_ONLY and the caller is neither its owner nor an Invitee
            - 400 `vote.ended`: the Poll has Ended; Ballots are frozen
            - 400 `vote.not_allowed`: the Poll is still Scheduled
            - 400 `vote.multiple.not_allowed`: `optionIds` holds more than one ID
            - 404 `vote.option.not_found`: the option ID does not exist
            - 400 `vote.option.not_in_vote`: the option belongs to another Poll
            - 404 `user.not_found`: the signed-in user no longer exists
            - 400 `common.validation_failed`: `optionIds` is empty
            - 429 `common.rate_limited`: more than 30 Ballot writes (cast, Retract, remove Selection) by the caller within 60 seconds; no `Retry-After`
            - 409 `common.conflict`: the Poll stayed busy with other changes for 3 seconds; nothing was recorded, try again
            """,
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    examples = {
                            @ExampleObject(name = "Choose Java", value = """
                                    { "optionIds": [ "0199f2a4-8d11-7c3e-b0a4-5e29c7d6f813" ] }
                                    """),
                            @ExampleObject(name = "Choose Java anonymously", value = """
                                    { "optionIds": [ "0199f2a4-8d11-7c3e-b0a4-5e29c7d6f813" ], "anonymous": true }
                                    """)
                    })))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Ballot recorded; Poll with results Sealed",
            content = @Content(examples = @ExampleObject(name = "Open Poll (Sealed)", value = EX_POLL_OPEN)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Poll has Ended (`vote.ended`), is still Scheduled (`vote.not_allowed`), or the Selection is invalid (`vote.multiple.not_allowed`, `vote.option.not_in_vote`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "vote.ended", value = ERR_MESSAGE + "Voting for this Poll has ended" + ERR_CODE + "vote.ended" + ERR_ERROR + "Voting for this Poll has ended" + ERR_END)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Poll does not exist, is Deleted or hidden from the caller, or option does not exist (`vote.not_found`, `vote.option.not_found`, `user.not_found`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "vote.not_found", value = ERR_MESSAGE + "Poll not found" + ERR_CODE + "vote.not_found" + ERR_ERROR + "Poll not found" + ERR_END)))
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
            **Side effects**: Turnout -1 when a Ballot was removed. A Participant who Retracted gets no Ended Notification, unless they are an Invitee: every Invitee of an INVITE_ONLY Poll still gets it.
            **Errors**:
            - 404 `vote.not_found`: Poll does not exist, is Deleted, or is INVITE_ONLY and the caller is neither its owner nor an Invitee
            - 400 `vote.ended`: the Poll has Ended; Ballots are frozen
            - 400 `vote.not_allowed`: the Poll is still Scheduled
            - 429 `common.rate_limited`: more than 30 Ballot writes (cast, Retract, remove Selection) by the caller within 60 seconds; no `Retry-After`
            - 409 `common.conflict`: the Poll stayed busy with other changes for 3 seconds; nothing was recorded, try again
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Ballot withdrawn (or none existed); `myOptionId` is null",
            content = @Content(examples = @ExampleObject(name = "Open Poll (Sealed)", value = EX_POLL_OPEN_NO_BALLOT)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Poll has Ended (`vote.ended`) or is still Scheduled (`vote.not_allowed`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "vote.ended", value = ERR_MESSAGE + "Voting for this Poll has ended" + ERR_CODE + "vote.ended" + ERR_ERROR + "Voting for this Poll has ended" + ERR_END)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Poll does not exist, is Deleted, or is Invite-only and hidden from the caller (`vote.not_found`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "vote.not_found", value = ERR_MESSAGE + "Poll not found" + ERR_CODE + "vote.not_found" + ERR_ERROR + "Poll not found" + ERR_END)))
    public ResponseEntity<ApiResponse<VoteResponse>> retract(
            @Parameter(description = "Poll ID", required = true, example = "0199f2a3-5b7e-7d40-a1c8-9e3b2f6d4c05")
            @PathVariable String voteId,
            @AuthenticationPrincipal UserPrincipal principal) {
        VoteResponse response = voteService.retract(voteId, principal.userId());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @DeleteMapping("/{voteId}/vote/{optionId}")
    @CommonApiResponses
    @Operation(summary = "Retract a Ballot by option", deprecated = true, description = """
            **Auth**: required
            **Precondition**: the Poll is Open (not Scheduled, not Ended)
            **Behavior**: kept for compatibility; withdraws the caller's Ballot only if it chose `optionId` (same effect as a Retraction then). \
            Succeeds without change when the caller's Ballot chose another option or none exists. Deprecated: use `DELETE /api/votes/{voteId}/vote`.
            **Side effects**: Turnout -1 when a Ballot was removed.
            **Errors**:
            - 404 `vote.not_found`: Poll does not exist, is Deleted, or is INVITE_ONLY and the caller is neither its owner nor an Invitee
            - 400 `vote.ended`: the Poll has Ended; Ballots are frozen
            - 400 `vote.not_allowed`: the Poll is still Scheduled
            - 429 `common.rate_limited`: more than 30 Ballot writes (cast, Retract, remove Selection) by the caller within 60 seconds; no `Retry-After`
            - 409 `common.conflict`: the Poll stayed busy with other changes for 3 seconds; nothing was recorded, try again
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Poll after the removal",
            content = @Content(examples = @ExampleObject(name = "Open Poll (Sealed)", value = EX_POLL_OPEN)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Poll has Ended (`vote.ended`) or is still Scheduled (`vote.not_allowed`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "vote.ended", value = ERR_MESSAGE + "Voting for this Poll has ended" + ERR_CODE + "vote.ended" + ERR_ERROR + "Voting for this Poll has ended" + ERR_END)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Poll does not exist, is Deleted, or is Invite-only and hidden from the caller (`vote.not_found`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "vote.not_found", value = ERR_MESSAGE + "Poll not found" + ERR_CODE + "vote.not_found" + ERR_ERROR + "Poll not found" + ERR_END)))
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
            - 404 `vote.not_found`: Poll does not exist, is Deleted, or is INVITE_ONLY and the caller is neither its owner nor an Invitee
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
                        "deadline": "2026-10-19T09:00:00+08:00",
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
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "vote.results.sealed", value = ERR_MESSAGE + "Results are sealed until the Poll ends" + ERR_CODE + "vote.results.sealed" + ERR_ERROR + "Results are sealed until the Poll ends" + ERR_END)))
    public ResponseEntity<ApiResponse<VoteResultResponse>> getVoteResults(
            @Parameter(description = "Poll ID", required = true, example = "0199f2a3-5b7e-7d40-a1c8-9e3b2f6d4c05")
            @PathVariable String voteId,
            @AuthenticationPrincipal UserPrincipal principal) {
        VoteResultResponse response = voteService.getVoteResults(voteId, principal != null ? principal.userId() : null);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping("/{voteId}/voters")
    @CommonApiResponses
    @Operation(summary = "List Participants of an Ended Poll", description = """
            **Auth**: required
            **Precondition**: the Poll has Ended, and its Voter Visibility allows the caller: NOBODY allows no one (not even the owner), OWNER allows only the owner, SIGNED_IN allows anyone who can see the Poll (for INVITE_ONLY: the owner and Invitees)
            **Behavior**: cursor-paged list of who chose which option, oldest Ballot first. Anonymous Ballots are left out, so the list can be shorter than `participantCount`. \
            To keep them anonymous, the whole list is hidden (403 `vote.voters.hidden`) while the Poll holds 1 or 2 Anonymous Ballots; with none, or with 3 or more, it is shown. \
            `nextCursor` null means the last page; `limit` is clamped to 1-50 (default 20).
            **Side effects**: none
            **Errors**:
            - 404 `vote.not_found`: Poll does not exist, is Deleted, or is INVITE_ONLY and the caller is neither its owner nor an Invitee
            - 403 `vote.results.sealed`: the Poll is still Scheduled or Open
            - 403 `vote.voters.hidden`: Voter Visibility does not allow the caller, or the Poll holds 1 or 2 Anonymous Ballots
            - 400 `common.cursor_invalid`: `cursor` is malformed or was issued by another list or another ordering of this list
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
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Poll not Ended (`vote.results.sealed`), or Voter Visibility does not allow the caller or the Poll holds 1 or 2 Anonymous Ballots (`vote.voters.hidden`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "vote.voters.hidden", value = ERR_MESSAGE + "Voters of this Poll are hidden" + ERR_CODE + "vote.voters.hidden" + ERR_ERROR + "Voters of this Poll are hidden" + ERR_END)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Poll does not exist, is Deleted, or is Invite-only and hidden from the caller (`vote.not_found`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "vote.not_found", value = ERR_MESSAGE + "Poll not found" + ERR_CODE + "vote.not_found" + ERR_ERROR + "Poll not found" + ERR_END)))
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
    @Operation(summary = "Get my Ballot status", deprecated = true, description = """
            **Auth**: required
            **Precondition**: none
            **Behavior**: deprecated, use `myOptionId` in `GET /api/votes/{voteId}`. Tells whether the caller holds a Ballot in the Poll and which option it chose. Works in any Poll state.
            **Side effects**: none
            **Errors**:
            - 404 `vote.not_found`: Poll does not exist, is Deleted, or is INVITE_ONLY and the caller is neither its owner nor an Invitee
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

    @DeleteMapping("/{voteId}")
    @Auditable(action = "DELETE", resourceType = "VOTE", resourceIdIndex = 0)
    @CommonApiResponses
    @Operation(summary = "Delete a Poll", description = """
            **Auth**: required (owner only)
            **Precondition**: the Poll is Scheduled, or is still within the delete window after posting (default 20 minutes, configurable; any state, even with Ballots); \
            `deletable` on the Poll response tells the owner whether this still holds
            **Behavior**: soft-deletes the Poll. From then on it is gone for everyone, owner included: every Poll endpoint \
            (including Comments, Replies, likes and notification read) returns 404 `vote.not_found`, and it leaves Feed / Explore / Search, \
            profile lists, My Polls, Ended Notifications and popular tag counts. The data is kept.
            **Side effects**: nobody is notified
            **Errors**:
            - 400 `vote.delete.window_passed`: the Poll is Open or Ended and the delete window (default 20 minutes, configurable) has passed
            - 403 `vote.forbidden`: the caller is not the owner
            - 404 `vote.not_found`: Poll does not exist or is already Deleted
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Poll deleted",
            content = @Content(examples = @ExampleObject(name = "Deleted", value = """
                    { "success": true, "data": { "success": true, "id": null, "status": null, "message": null },
                      "message": null, "errorCode": null, "error": null }
                    """)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Delete window has passed (`vote.delete.window_passed`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "vote.delete.window_passed", description = "Message rendered with the configured delete window (default 20 minutes shown)", value = ERR_MESSAGE + "A Poll can only be deleted while it is scheduled or within 20 minutes of posting" + ERR_CODE + "vote.delete.window_passed" + ERR_ERROR + "A Poll can only be deleted while it is scheduled or within 20 minutes of posting" + ERR_END)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not the owner (`vote.forbidden`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "vote.forbidden", value = ERR_MESSAGE + "Not allowed to modify this Poll" + ERR_CODE + "vote.forbidden" + ERR_ERROR + "Not allowed to modify this Poll" + ERR_END)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Poll does not exist or is already Deleted (`vote.not_found`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "vote.not_found", value = ERR_MESSAGE + "Poll not found" + ERR_CODE + "vote.not_found" + ERR_ERROR + "Poll not found" + ERR_END)))
    public ResponseEntity<ApiResponse<SimpleResultResponse>> deleteVote(
            @Parameter(description = "Poll ID", required = true, example = "0199f2a3-5b7e-7d40-a1c8-9e3b2f6d4c05")
            @PathVariable String voteId,
            @AuthenticationPrincipal UserPrincipal principal) {
        voteService.deleteVote(voteId, principal.userId());
        return ResponseEntity.ok(ApiResponse.ok(SimpleResultResponse.ok()));
    }

    @PutMapping("/{voteId}/deactivate")
    @Auditable(action = "DEACTIVATE", resourceType = "VOTE", resourceIdIndex = 0)
    @CommonApiResponses
    @Operation(summary = "Close a Poll", description = """
            **Auth**: required (owner only)
            **Precondition**: the Poll is Open and its Audience is not Public (Unlisted or Invite-only); an Ended Poll is accepted as a no-op
            **Behavior**: Open Poll: ends now (Ended); `endTime` becomes the close time while `deadline` keeps the owner's end time. \
            Ended Poll: nothing changes. A Public Poll runs to its Deadline; a Scheduled Poll is Deleted instead. Fetch the Poll again to see the new state.
            **Side effects**: the Poll becomes Ended, so results and Voter Visibility open up; the owner, Participants and Invitees receive an Ended Notification.
            **Errors**:
            - 400 `vote.close.public_not_allowed`: the Poll is Public and Open
            - 400 `vote.close.scheduled`: the Poll is Scheduled; use `DELETE /api/votes/{voteId}` instead
            - 403 `vote.forbidden`: the caller is not the owner
            - 404 `vote.not_found`: Poll does not exist, is Deleted, or is INVITE_ONLY and the caller is neither its owner nor an Invitee
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Poll closed (or already Ended)",
            content = @Content(examples = @ExampleObject(name = "Closed", value = """
                    { "success": true, "data": { "success": true, "id": null, "status": null, "message": null },
                      "message": null, "errorCode": null, "error": null }
                    """)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Poll is Public (`vote.close.public_not_allowed`) or Scheduled (`vote.close.scheduled`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF), examples = {
                    @ExampleObject(name = "vote.close.public_not_allowed", value = ERR_MESSAGE + "A Public Poll cannot be closed early; it runs until its deadline" + ERR_CODE + "vote.close.public_not_allowed" + ERR_ERROR + "A Public Poll cannot be closed early; it runs until its deadline" + ERR_END),
                    @ExampleObject(name = "vote.close.scheduled", value = ERR_MESSAGE + "A scheduled Poll cannot be closed; delete it instead" + ERR_CODE + "vote.close.scheduled" + ERR_ERROR + "A scheduled Poll cannot be closed; delete it instead" + ERR_END)}))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not the owner (`vote.forbidden`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "vote.forbidden", value = ERR_MESSAGE + "Not allowed to modify this Poll" + ERR_CODE + "vote.forbidden" + ERR_ERROR + "Not allowed to modify this Poll" + ERR_END)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Poll does not exist, is Deleted, or is Invite-only and hidden from the caller (`vote.not_found`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "vote.not_found", value = ERR_MESSAGE + "Poll not found" + ERR_CODE + "vote.not_found" + ERR_ERROR + "Poll not found" + ERR_END)))
    public ResponseEntity<ApiResponse<SimpleResultResponse>> deactivateVote(
            @Parameter(description = "Poll ID", required = true, example = "0199f2a3-5b7e-7d40-a1c8-9e3b2f6d4c05")
            @PathVariable String voteId,
            @AuthenticationPrincipal UserPrincipal principal) {
        voteService.closeVote(voteId, principal.userId());
        log.info("Vote {} closed by creator {}", voteId, principal.userId());
        return ResponseEntity.ok(ApiResponse.ok(SimpleResultResponse.ok()));
    }

    private static final String EX_INVITEES = """
            { "success": true, "data": [
                { "userId": "0199f2a1-2c5d-7f13-9b0e-7d4a1e3f6c82", "username": "bob", "email": null, "hasBallot": true, "invitedAt": "2026-10-09T15:20:31+08:00" },
                { "userId": "0199f2a1-3d6e-7a24-8c1f-8e5b2f4a7d93", "username": "carol", "email": null, "hasBallot": false, "invitedAt": "2026-10-09T15:20:31+08:00" },
                { "userId": null, "username": null, "email": "dave@example.com", "hasBallot": false, "invitedAt": "2026-10-09T15:20:31+08:00" }
              ], "message": null, "errorCode": null, "error": null }
            """;

    @GetMapping("/{voteId}/invitees")
    @CommonApiResponses
    @Operation(summary = "List Invitees", description = """
            **Auth**: required (owner only)
            **Precondition**: none; the list is empty for a Poll that is not INVITE_ONLY
            **Behavior**: the Poll's Invitees, earliest invited first, each with `hasBallot` so the owner sees who has not answered yet. \
            An Invitee added by email is always listed by that address with `userId` / `username` null, whether or not the address belongs to an account \
            (`hasBallot` turns true once that person casts a Ballot). \
            Never tells which option anyone chose, so Sealed results stay sealed.
            **Side effects**: none
            **Errors**:
            - 403 `vote.forbidden`: the caller is not the owner (Invitees included)
            - 404 `vote.not_found`: Poll does not exist, is Deleted, or is INVITE_ONLY and the caller is neither its owner nor an Invitee
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Invitees of the Poll",
            content = @Content(examples = @ExampleObject(name = "Invitees", value = EX_INVITEES)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not the owner (`vote.forbidden`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "vote.forbidden", value = ERR_MESSAGE + "Not allowed to modify this Poll" + ERR_CODE + "vote.forbidden" + ERR_ERROR + "Not allowed to modify this Poll" + ERR_END)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Poll does not exist, is Deleted, or is Invite-only and hidden from the caller (`vote.not_found`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "vote.not_found", value = ERR_MESSAGE + "Poll not found" + ERR_CODE + "vote.not_found" + ERR_ERROR + "Poll not found" + ERR_END)))
    public ResponseEntity<ApiResponse<List<InviteeResponse>>> getInvitees(
            @Parameter(description = "Poll ID", required = true, example = "0199f2a3-5b7e-7d40-a1c8-9e3b2f6d4c05")
            @PathVariable String voteId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(voteService.getInvitees(voteId, principal.userId())));
    }

    @PostMapping("/{voteId}/invitees")
    @Auditable(action = "INVITE", resourceType = "VOTE", resourceIdIndex = 0)
    @CommonApiResponses
    @Operation(summary = "Add Invitees", description = """
            **Auth**: required (owner only)
            **Precondition**: the Poll is INVITE_ONLY and Scheduled or Open (Invitees can still be added after it opened)
            **Behavior**: adds the users and email addresses as Invitees, so they can see the Poll, cast a Ballot and Comment, and find it in My Polls. \
            Users or addresses already invited, duplicates and the owner's own ID / address are ignored. \
            An address is always listed as entered, whether or not it belongs to an account; an active account's user gets access right away, any other address once someone verifies it at sign-up / sign-in. \
            `groupIds` invites the current members of the caller's Groups: members added by ID join `userIds` (suspended ones are skipped) and members added by email \
            join `emails` by their stored address; later Group changes never touch the Poll. Returns the full Invitee list.
            **Side effects**: `inviteeCount` grows (email Invitees included); each new Invitee tied to a user gets an unread Invitation notification \
            (again unread for one removed and invited again) and later the Ended Notification; each new email Invitee is sent an invitation email \
            (at most once per address per Poll, even after removing and re-adding it, and at most `EMAIL_INVITATION_DAILY_LIMIT` (default 200) per owner per day; \
            over the limit the Invitee is still added and only the email is skipped) \
            linking to the Poll (only when email is enabled) and gets both notifications once tied to a user; the action is audit-logged.
            **Errors**:
            - 400 `vote.invitee.not_invite_only`: the Poll is not INVITE_ONLY
            - 400 `vote.ended`: the Poll has Ended
            - 400 `vote.invitee.ids_invalid`: one of `userIds` is not an existing user or is suspended (`emails` and `groupIds` never cause it)
            - 400 `common.validation_failed`: `userIds`, `emails` and `groupIds` are all empty, `userIds` / `emails` hold more than 100 entries or `groupIds` more than 20, a null element or an invalid address
            - 403 `vote.forbidden`: the caller is not the owner
            - 404 `vote.not_found`: Poll does not exist, is Deleted, or is INVITE_ONLY and the caller is neither its owner nor an Invitee
            - 404 `group.not_found`: one of `groupIds` does not exist or belongs to someone else
            """,
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    examples = {@ExampleObject(name = "Invite carol and dave", value = """
                            { "userIds": [ "0199f2a1-3d6e-7a24-8c1f-8e5b2f4a7d93" ], "emails": [ "dave@example.com" ] }
                            """), @ExampleObject(name = "Invite a Group", value = """
                            { "groupIds": [ "0199f2b0-7a1c-7e45-9b3d-2c8f6a4e1d07" ] }
                            """)})))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Invitees after the addition",
            content = @Content(examples = @ExampleObject(name = "Invitees", value = EX_INVITEES)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Poll is not Invite-only (`vote.invitee.not_invite_only`), has Ended (`vote.ended`), or a user does not exist (`vote.invitee.ids_invalid`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "vote.invitee.ids_invalid", value = ERR_MESSAGE + "One or more invited users do not exist or are suspended" + ERR_CODE + "vote.invitee.ids_invalid" + ERR_ERROR + "One or more invited users do not exist or are suspended" + ERR_END)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not the owner (`vote.forbidden`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "vote.forbidden", value = ERR_MESSAGE + "Not allowed to modify this Poll" + ERR_CODE + "vote.forbidden" + ERR_ERROR + "Not allowed to modify this Poll" + ERR_END)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Poll does not exist, is Deleted, or is Invite-only and hidden from the caller (`vote.not_found`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "vote.not_found", value = ERR_MESSAGE + "Poll not found" + ERR_CODE + "vote.not_found" + ERR_ERROR + "Poll not found" + ERR_END)))
    public ResponseEntity<ApiResponse<List<InviteeResponse>>> addInvitees(
            @Parameter(description = "Poll ID", required = true, example = "0199f2a3-5b7e-7d40-a1c8-9e3b2f6d4c05")
            @PathVariable String voteId,
            @Valid @RequestBody InviteesRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(voteService.addInvitees(voteId, request, principal.userId())));
    }

    @DeleteMapping("/{voteId}/invitees/{userId}")
    @Auditable(action = "UNINVITE", resourceType = "VOTE", resourceIdIndex = 0)
    @CommonApiResponses
    @Operation(summary = "Remove an Invitee", description = """
            **Auth**: required (owner only)
            **Precondition**: the Poll is INVITE_ONLY and Scheduled or Open, and the Invitee holds no Ballot
            **Behavior**: removes the Invitee added by this user ID; from then on the Poll is 404 `vote.not_found` for them. \
            Idempotent: succeeds, changing nothing, when the user is not an Invitee added by ID; one added by email \
            is removed through `DELETE /api/votes/{voteId}/invitees?email=`. Returns the full Invitee list.
            **Side effects**: `inviteeCount` drops; the action is audit-logged. When every remaining Invitee of an Open Poll holds a Ballot \
            (and at least one remains), the Poll Ends now (`endTime` moves, `deadline` stays) and the Ended Notification follows. \
            This check also runs when nothing was removed, so even a no-op call can end such a Poll.
            **Errors**:
            - 400 `vote.invitee.has_ballot`: the Invitee holds a Ballot, so counted Ballots are never orphaned
            - 400 `vote.invitee.not_invite_only`: the Poll is not INVITE_ONLY
            - 400 `vote.ended`: the Poll has Ended
            - 403 `vote.forbidden`: the caller is not the owner
            - 404 `vote.not_found`: Poll does not exist, is Deleted, or is INVITE_ONLY and the caller is neither its owner nor an Invitee
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Invitees after the removal",
            content = @Content(examples = @ExampleObject(name = "Invitees", value = EX_INVITEES)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invitee holds a Ballot (`vote.invitee.has_ballot`), Poll is not Invite-only (`vote.invitee.not_invite_only`) or has Ended (`vote.ended`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "vote.invitee.has_ballot", value = ERR_MESSAGE + "An Invitee who holds a Ballot cannot be removed" + ERR_CODE + "vote.invitee.has_ballot" + ERR_ERROR + "An Invitee who holds a Ballot cannot be removed" + ERR_END)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not the owner (`vote.forbidden`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "vote.forbidden", value = ERR_MESSAGE + "Not allowed to modify this Poll" + ERR_CODE + "vote.forbidden" + ERR_ERROR + "Not allowed to modify this Poll" + ERR_END)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Poll does not exist, is Deleted, or is Invite-only and hidden from the caller (`vote.not_found`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "vote.not_found", value = ERR_MESSAGE + "Poll not found" + ERR_CODE + "vote.not_found" + ERR_ERROR + "Poll not found" + ERR_END)))
    public ResponseEntity<ApiResponse<List<InviteeResponse>>> removeInvitee(
            @Parameter(description = "Poll ID", required = true, example = "0199f2a3-5b7e-7d40-a1c8-9e3b2f6d4c05")
            @PathVariable String voteId,
            @Parameter(description = "User ID of the Invitee", required = true, example = "0199f2a1-3d6e-7a24-8c1f-8e5b2f4a7d93")
            @PathVariable String userId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(voteService.removeInvitee(voteId, userId, principal.userId())));
    }

    @DeleteMapping(value = "/{voteId}/invitees", params = "email")
    @Auditable(action = "UNINVITE", resourceType = "VOTE", resourceIdIndex = 0)
    @CommonApiResponses
    @Operation(summary = "Remove an email Invitee", description = """
            **Auth**: required (owner only)
            **Precondition**: the Poll is INVITE_ONLY and Scheduled or Open, and the Invitee holds no Ballot
            **Behavior**: removes the Invitee added by this address (normalized like sign-in does: lower-cased, trimmed, Gmail in canonical form), whether or not it belongs to an account; \
            from then on the Poll is 404 `vote.not_found` for that person and verifying the address no longer grants access. \
            Idempotent: succeeds, changing nothing, when the address is not an Invitee. Returns the full Invitee list.
            **Side effects**: `inviteeCount` drops; the action is audit-logged. When every remaining Invitee of an Open Poll holds a Ballot \
            (and at least one remains), the Poll Ends now (`endTime` moves, `deadline` stays) and the Ended Notification follows. \
            This check also runs when nothing was removed, so even a no-op call can end such a Poll.
            **Errors**:
            - 400 `vote.invitee.has_ballot`: the Invitee holds a Ballot, so counted Ballots are never orphaned
            - 400 `vote.invitee.not_invite_only`: the Poll is not INVITE_ONLY
            - 400 `vote.ended`: the Poll has Ended
            - 403 `vote.forbidden`: the caller is not the owner
            - 404 `vote.not_found`: Poll does not exist, is Deleted, or is INVITE_ONLY and the caller is neither its owner nor an Invitee
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Invitees after the removal",
            content = @Content(examples = @ExampleObject(name = "Invitees", value = EX_INVITEES)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invitee holds a Ballot (`vote.invitee.has_ballot`), Poll is not Invite-only (`vote.invitee.not_invite_only`) or has Ended (`vote.ended`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "vote.invitee.has_ballot", value = ERR_MESSAGE + "An Invitee who holds a Ballot cannot be removed" + ERR_CODE + "vote.invitee.has_ballot" + ERR_ERROR + "An Invitee who holds a Ballot cannot be removed" + ERR_END)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not the owner (`vote.forbidden`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "vote.forbidden", value = ERR_MESSAGE + "Not allowed to modify this Poll" + ERR_CODE + "vote.forbidden" + ERR_ERROR + "Not allowed to modify this Poll" + ERR_END)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Poll does not exist, is Deleted, or is Invite-only and hidden from the caller (`vote.not_found`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "vote.not_found", value = ERR_MESSAGE + "Poll not found" + ERR_CODE + "vote.not_found" + ERR_ERROR + "Poll not found" + ERR_END)))
    public ResponseEntity<ApiResponse<List<InviteeResponse>>> removeEmailInvitee(
            @Parameter(description = "Poll ID", required = true, example = "0199f2a3-5b7e-7d40-a1c8-9e3b2f6d4c05")
            @PathVariable String voteId,
            @Parameter(description = "Email address the Invitee was added by", required = true, example = "dave@example.com")
            @RequestParam String email,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(voteService.removeEmailInvitee(voteId, email, principal.userId())));
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
