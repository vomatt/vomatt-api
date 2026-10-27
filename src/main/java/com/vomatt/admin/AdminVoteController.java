package com.vomatt.admin;

import java.util.UUID;

import com.vomatt.admin.dto.AdminVoteResponse;
import com.vomatt.common.annotation.CommonApiResponses;
import com.vomatt.common.audit.Auditable;
import com.vomatt.common.config.OpenAPIConfig;
import com.vomatt.common.response.ApiResponse;
import com.vomatt.common.response.PageResponse;
import com.vomatt.common.response.SimpleResultResponse;
import com.vomatt.common.security.UserPrincipal;
import com.vomatt.entity.Audience;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/votes")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - Poll", description = "Poll moderation APIs (Admin)")
public class AdminVoteController {

    private static final String ADMIN_VOTE_DATA = """
            {
                "vote": {
                  "id": "0199f2a3-5b7e-7d40-a1c8-9e3b2f6d4c05",
                  "title": "Which language should we use for the next side project?",
                  "description": null,
                  "creatorId": "0199f2a1-1b4c-7e02-8a9d-6c3f0d2e5b71",
                  "creatorUsername": "alice",
                  "creatorAvatarUrl": "bot:boba",
                  "startTime": "2026-10-12T09:00:00+08:00",
                  "endTime": "2026-10-19T09:00:00+08:00",
                  "deadline": "2026-10-19T09:00:00+08:00",
                  "active": true,
                  "allowMultipleChoices": false,
                  "anonymous": false,
                  "createdAt": "2026-10-09T15:20:31+08:00",
                  "updatedAt": "2026-10-09T15:20:31+08:00",
                  "totalVotes": 42,
                  "participantCount": 42,
                  "votingActive": true,
                  "options": [
                    { "id": "0199f2a4-8d11-7c3e-b0a4-5e29c7d6f813", "text": "Java", "description": null, "displayOrder": 0, "createdAt": "2026-10-09T15:20:31+08:00", "votes": null },
                    { "id": "0199f2a4-8d11-7c3e-b0a4-5e29c7d6f814", "text": "Go", "description": null, "displayOrder": 1, "createdAt": "2026-10-09T15:20:31+08:00", "votes": null }
                  ],
                  "tags": [],
                  "commentCount": 7,
                  "shareCount": 18,
                  "voterVisibility": "OWNER",
                  "audience": "PUBLIC",
                  "inviteeCount": null,
                  "unread": null,
                  "myOptionId": null,
                  "myBallotAnonymous": null,
                  "deletable": null
                },
                "deletedAt": null,
                "creatorEmail": "alice@example.com",
                "creatorActive": true
              }""";

    private static final String VOTE_NOT_FOUND_EXAMPLE = """
            {
              "success": false,
              "data": null,
              "message": "Vote not found",
              "errorCode": "vote.not_found",
              "error": "Vote not found"
            }""";

    private final AdminVoteService adminVoteService;

    @GetMapping
    @CommonApiResponses
    @Operation(summary = "List Polls",
            description = """
                    **Auth**: admin (role ADMIN; 403 `common.forbidden` otherwise)
                    **Precondition**: caller holds role ADMIN
                    **Behavior**: offset page of every Poll, any Audience and Deleted ones included, newest created first. Filters (all optional, combined with AND): `q` searches title and description (at least 2 characters); `status` is `scheduled`, `open` or `ended`, derived from the times; `creatorId`; `audience` (`PUBLIC`, `UNLISTED`, `INVITE_ONLY`); `deleted` true for Deleted only, false for not Deleted only. Results stay Sealed: `vote.options[].votes` is null until the Poll has Ended. `page` is 1-based (default 1), `limit` 1-50 (default 20)
                    **Side effects**: none
                    **Errors**:
                    - 400 `common.invalid_status`: `status` is not `scheduled`, `open` or `ended`
                    - 400 `common.type_mismatch`: `creatorId`, `audience` or `deleted` is malformed
                    - 400 `vote.search.query_too_short`: `q` is a single character""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Page of Polls",
            content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Polls", value = """
                    {
                      "success": true,
                      "data": {
                        "content": [
                    """ + ADMIN_VOTE_DATA + """

                        ],
                        "total": 1,
                        "page": 1,
                        "limit": 20
                      },
                      "message": null,
                      "errorCode": null,
                      "error": null
                    }""")))
    public ResponseEntity<ApiResponse<PageResponse<AdminVoteResponse>>> listVotes(
            @Parameter(description = "Search text on title and description, at least 2 characters", example = "language")
            @RequestParam(required = false) String q,
            @Parameter(description = "`scheduled`, `open` or `ended`; omit for any", example = "open")
            @RequestParam(required = false) String status,
            @Parameter(description = "Only Polls created by this user (UUID)")
            @RequestParam(required = false) UUID creatorId,
            @Parameter(description = "Only Polls with this Audience", example = "INVITE_ONLY")
            @RequestParam(required = false) Audience audience,
            @Parameter(description = "true: Deleted only; false: not Deleted only; omit for both", example = "true")
            @RequestParam(required = false) Boolean deleted,
            @Parameter(description = "Page number, 1-based (default 1)") @RequestParam(required = false) Integer page,
            @Parameter(description = "Page size (1-50, default 20)") @RequestParam(required = false) Integer limit) {
        return ResponseEntity.ok(ApiResponse.ok(adminVoteService.listVotes(q, status, creatorId, audience, deleted, page, limit)));
    }

    @GetMapping("/{voteId}")
    @CommonApiResponses
    @Operation(summary = "Get Poll",
            description = """
                    **Auth**: admin (role ADMIN; 403 `common.forbidden` otherwise)
                    **Precondition**: caller holds role ADMIN
                    **Behavior**: returns any Poll, Deleted and Invite-only included, with when it was Deleted and the owner's email and suspension state. Results stay Sealed until the Poll has Ended
                    **Side effects**: none
                    **Errors**:
                    - 400 `common.type_mismatch`: `voteId` is not a valid UUID
                    - 404 `vote.not_found`: no Poll with this ID""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "The Poll",
            content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Poll", value = """
                    {
                      "success": true,
                      "data": """ + ADMIN_VOTE_DATA + """
                    ,
                      "message": null,
                      "errorCode": null,
                      "error": null
                    }""")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Poll not found (`vote.not_found`)",
            content = @Content(mediaType = "application/json", schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "VoteNotFound", value = VOTE_NOT_FOUND_EXAMPLE)))
    public ResponseEntity<ApiResponse<AdminVoteResponse>> getVote(
            @Parameter(description = "Poll ID (UUIDv7)", required = true) @PathVariable UUID voteId) {
        return ResponseEntity.ok(ApiResponse.ok(adminVoteService.getVote(voteId)));
    }

    @PutMapping("/{voteId}/close")
    @Auditable(action = "FORCE_CLOSE", resourceType = "VOTE", resourceIdIndex = 0)
    @CommonApiResponses
    @Operation(summary = "Close Poll",
            description = """
                    **Auth**: admin (role ADMIN; 403 `common.forbidden` otherwise)
                    **Precondition**: the Poll exists, is not Deleted, and is Open with Audience UNLISTED or INVITE_ONLY, the same rules as the owner's Close (to take down a Public or Scheduled Poll, Delete it)
                    **Behavior**: Closes the Poll for its owner: it Ends now and keeps its Deadline. An Ended Poll is left as is (idempotent). Returns the Poll, whose results are now unsealed
                    **Side effects**: Participants see the Poll Ended in their notifications. Written to the audit log
                    **Errors**:
                    - 400 `common.type_mismatch`: `voteId` is not a valid UUID
                    - 400 `vote.close.public_not_allowed`: the Poll is Public
                    - 400 `vote.close.scheduled`: the Poll is Scheduled
                    - 404 `vote.not_found`: no Poll with this ID, or the Poll is Deleted (Restore it first)
                    - 409 `common.conflict`: the Poll is busy (a Ballot or another change holds it); retry""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Poll Closed",
            content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Closed", value = """
                    {
                      "success": true,
                      "data": """ + ADMIN_VOTE_DATA + """
                    ,
                      "message": null,
                      "errorCode": null,
                      "error": null
                    }""")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Public (`vote.close.public_not_allowed`) or Scheduled (`vote.close.scheduled`) Poll, or malformed `voteId` (`common.type_mismatch`)",
            content = @Content(mediaType = "application/json", schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "ClosePublic", value = """
                            {
                              "success": false,
                              "data": null,
                              "message": "A Public Poll cannot be closed early; it runs until its deadline",
                              "errorCode": "vote.close.public_not_allowed",
                              "error": "A Public Poll cannot be closed early; it runs until its deadline"
                            }""")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Poll not found (`vote.not_found`)",
            content = @Content(mediaType = "application/json", schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "VoteNotFound", value = VOTE_NOT_FOUND_EXAMPLE)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Poll busy (`common.conflict`)",
            content = @Content(mediaType = "application/json", schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "Conflict", value = """
                            {
                              "success": false,
                              "data": null,
                              "message": "Resource conflict, please try again",
                              "errorCode": "common.conflict",
                              "error": "Resource conflict, please try again"
                            }""")))
    public ResponseEntity<ApiResponse<AdminVoteResponse>> closeVote(
            @Parameter(description = "Poll ID (UUIDv7)", required = true) @PathVariable UUID voteId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(adminVoteService.closeVote(voteId, principal.userId())));
    }

    @DeleteMapping("/{voteId}")
    @Auditable(action = "FORCE_DELETE", resourceType = "VOTE", resourceIdIndex = 0)
    @CommonApiResponses
    @Operation(summary = "Delete Poll",
            description = """
                    **Auth**: admin (role ADMIN; 403 `common.forbidden` otherwise)
                    **Precondition**: the Poll exists; any status, regardless of the owner's delete window
                    **Behavior**: Deletes the Poll (soft: the row and its Ballots are kept, `deletedAt` is set). A Deleted Poll is left as is (idempotent). Returns `{success: true}`
                    **Side effects**: the Poll is hidden from everyone, owner included, and its tags stop counting it. Nobody is notified. Reversible with Restore. Written to the audit log
                    **Errors**:
                    - 400 `common.type_mismatch`: `voteId` is not a valid UUID
                    - 404 `vote.not_found`: no Poll with this ID
                    - 409 `common.conflict`: the Poll is busy; retry""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Poll Deleted",
            content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Deleted", value = """
                    {
                      "success": true,
                      "data": {
                        "success": true,
                        "id": null,
                        "status": null,
                        "message": null
                      },
                      "message": null,
                      "errorCode": null,
                      "error": null
                    }""")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Poll not found (`vote.not_found`)",
            content = @Content(mediaType = "application/json", schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "VoteNotFound", value = VOTE_NOT_FOUND_EXAMPLE)))
    public ResponseEntity<ApiResponse<SimpleResultResponse>> deleteVote(
            @Parameter(description = "Poll ID (UUIDv7)", required = true) @PathVariable UUID voteId,
            @AuthenticationPrincipal UserPrincipal principal) {
        adminVoteService.deleteVote(voteId, principal.userId());
        return ResponseEntity.ok(ApiResponse.ok(SimpleResultResponse.ok()));
    }

    @PatchMapping("/{voteId}/restore")
    @Auditable(action = "RESTORE", resourceType = "VOTE", resourceIdIndex = 0)
    @CommonApiResponses
    @Operation(summary = "Restore Poll",
            description = """
                    **Auth**: admin (role ADMIN; 403 `common.forbidden` otherwise)
                    **Precondition**: the Poll exists
                    **Behavior**: undoes a Delete by clearing `deletedAt`; a Poll that is not Deleted is left as is (idempotent). Its times are unchanged, so it comes back in whatever status they give. Returns the Poll
                    **Side effects**: the Poll is visible again to everyone who could reach it before, and its tags count it again. Written to the audit log
                    **Errors**:
                    - 400 `common.type_mismatch`: `voteId` is not a valid UUID
                    - 404 `vote.not_found`: no Poll with this ID
                    - 409 `common.conflict`: the Poll is busy; retry""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Poll restored",
            content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Restored", value = """
                    {
                      "success": true,
                      "data": """ + ADMIN_VOTE_DATA + """
                    ,
                      "message": null,
                      "errorCode": null,
                      "error": null
                    }""")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Poll not found (`vote.not_found`)",
            content = @Content(mediaType = "application/json", schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "VoteNotFound", value = VOTE_NOT_FOUND_EXAMPLE)))
    public ResponseEntity<ApiResponse<AdminVoteResponse>> restoreVote(
            @Parameter(description = "Poll ID (UUIDv7)", required = true) @PathVariable UUID voteId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(adminVoteService.restoreVote(voteId, principal.userId())));
    }
}
