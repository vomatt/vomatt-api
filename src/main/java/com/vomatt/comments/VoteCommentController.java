package com.vomatt.comments;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.vomatt.comments.dto.CreateCommentRequest;
import com.vomatt.comments.dto.UpdateCommentRequest;
import com.vomatt.common.response.ApiResponse;
import com.vomatt.common.response.CursorResponse;
import com.vomatt.common.response.SimpleResultResponse;
import com.vomatt.common.annotation.CommonApiResponses;
import com.vomatt.common.annotation.PublicApiResponse;
import com.vomatt.comments.dto.CommentDto;
import com.vomatt.common.security.UserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.vomatt.comments.VoteCommentService;
import com.vomatt.common.audit.Auditable;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.vomatt.common.config.OpenAPIConfig;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
@RequestMapping("/api/votes/{voteId}/comments")
@RequiredArgsConstructor
@Tag(name = "Vote Comment", description = "Comments and Replies on a Poll (path segment `votes` = Poll)")
public class VoteCommentController {

    private static final Logger log = LoggerFactory.getLogger(VoteCommentController.class);
    private final VoteCommentService commentService;

    @PostMapping
    @Auditable(action = "CREATE", resourceType = "COMMENT")
    @CommonApiResponses
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Comment or Reply created (same shape as list items; `replyCount` is 0)",
            content = @Content(examples = @ExampleObject(name = "reply", value = """
                            {
                              "success": true,
                              "data": {
                              "id": "0199c3b7-92f4-7a15-8d20-5b7c1e4f3a68",
                              "voteId": "0199c2f0-4d3a-7e8b-b6c2-1a9f0e7d5c33",
                              "userId": "0199b8e4-1c2d-7f60-8a3b-6d4e2f1a9b05",
                              "author": "alice",
                              "text": "I agree with you.",
                              "createdAt": "2026-10-09T12:34:56+08:00",
                              "updatedAt": "2026-10-09T12:34:56+08:00",
                              "edited": false,
                              "likeCount": 0,
                              "likedByCurrentUser": false,
                              "parentId": "0199c3a2-7b1e-7c4d-9a10-3f5e8d2b6a41",
                              "replyCount": 0,
                              "deleted": false
                            },
                              "message": null,
                              "errorCode": null,
                              "error": null
                            }
                            """)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Poll or current user not found (errorCode `vote.not_found`, `user.not_found`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(ref = "#/components/examples/" + OpenAPIConfig.EX_NOT_FOUND)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "`comment.parent.invalid` when parentId is unusable (400 also covers `common.validation_failed`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "parentInvalid", value = """
                            {
                              "success": false,
                              "data": null,
                              "message": "The comment being replied to does not exist in this poll",
                              "errorCode": "comment.parent.invalid",
                              "error": "The comment being replied to does not exist in this poll"
                            }
                            """)))
    @Operation(summary = "Post a Comment or Reply on a Poll",
            description = """
                    **Auth**: required
                    **Precondition**: the Poll exists (any Poll state is accepted). When `parentId` is sent it must be a non-deleted Comment or Reply of the same Poll
                    **Behavior**: without `parentId` creates a top-level Comment. With `parentId` creates a Reply; Replies are one level deep, so replying to a Reply attaches the new Reply to that Reply's top-level Comment (the response `parentId` is the top-level Comment, not the id you sent). Responds 201
                    **Side effects**: the top-level Comment's `replyCount` grows by 1 in later list reads
                    **Errors**:
                    - 400 `comment.parent.invalid`: `parentId` does not exist, is deleted, or belongs to another Poll
                    - 404 `vote.not_found`: Poll does not exist
                    - 404 `user.not_found`: the signed-in user no longer exists
                    """,
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    examples = {
                            @ExampleObject(name = "topLevelComment", value = """
                                    { "text": "I would pick option B, it is the cheaper one." }
                                    """),
                            @ExampleObject(name = "reply", value = """
                                    { "text": "I agree with you.", "parentId": "0199c3a2-7b1e-7c4d-9a10-3f5e8d2b6a41" }
                                    """)
                    })))
    public ResponseEntity<ApiResponse<CommentDto>> createComment(
            @Parameter(description = "Poll ID", required = true) @PathVariable String voteId,
            @Valid @RequestBody CreateCommentRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        CommentDto response = commentService.createComment(voteId, principal.userId(), request);
        log.info("Comment created on vote {} by user {}", voteId, principal.userId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(response));
    }

    @GetMapping
    @PublicApiResponse
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success",
            content = @Content(examples = @ExampleObject(name = "commentPage", value = """
                            {
                              "success": true,
                              "data": {
                              "items": [
                                {
                                  "id": "0199c3a2-7b1e-7c4d-9a10-3f5e8d2b6a41",
                                  "voteId": "0199c2f0-4d3a-7e8b-b6c2-1a9f0e7d5c33",
                                  "userId": "0199b8e4-1c2d-7f60-8a3b-6d4e2f1a9b05",
                                  "author": "alice",
                                  "text": "I would pick option B, it is the cheaper one.",
                                  "createdAt": "2026-10-09T12:34:56+08:00",
                                  "updatedAt": "2026-10-09T12:34:56+08:00",
                                  "edited": false,
                                  "likeCount": 3,
                                  "likedByCurrentUser": false,
                                  "parentId": null,
                                  "replyCount": 2,
                                  "deleted": false
                                },
                                {
                                  "id": "0199c39e-1a2b-7d3c-8e4f-9a0b1c2d3e4f",
                                  "voteId": "0199c2f0-4d3a-7e8b-b6c2-1a9f0e7d5c33",
                                  "userId": null,
                                  "author": null,
                                  "text": null,
                                  "createdAt": "2026-10-09T12:20:00+08:00",
                                  "updatedAt": "2026-10-09T12:20:00+08:00",
                                  "edited": false,
                                  "likeCount": 0,
                                  "likedByCurrentUser": false,
                                  "parentId": null,
                                  "replyCount": 1,
                                  "deleted": true
                                }
                              ],
                              "nextCursor": "MDE5OWMzOWUtMWEyYi03ZDNjLThlNGYtOWEwYjFjMmQzZTRmfDIwMjYtMTAtMDlUMTI6MjA6MDArMDg6MDA"
                            },
                              "message": null,
                              "errorCode": null,
                              "error": null
                            }
                            """)))
    @Operation(summary = "List Comments of a Poll",
            description = """
                    **Auth**: public (optional login changes response: `likedByCurrentUser` reflects the signed-in user's likes, and is always false when not logged in; nothing else differs)
                    **Precondition**: the Poll exists
                    **Behavior**: returns top-level Comments only, newest first, cursor-paged (`limit` default 20, max 50; pass `nextCursor` back as `cursor`). Each item carries `replyCount`; load Replies with the replies endpoint. A deleted Comment that still has non-deleted Replies stays as a placeholder (`deleted` = true; `userId`, `author` and `text` are null); a deleted Comment without Replies is omitted
                    **Side effects**: none
                    **Errors**:
                    - 400 `common.cursor_invalid`: `cursor` is not a token returned by this endpoint
                    - 404 `vote.not_found`: Poll does not exist
                    """)
    public ResponseEntity<ApiResponse<CursorResponse<CommentDto>>> getComments(
            @Parameter(description = "Poll ID", required = true) @PathVariable String voteId,
            @Parameter(description = "`nextCursor` returned by the previous page; omit for the first page") @RequestParam(required = false) String cursor,
            @Parameter(description = "Page size, 1-50 (default 20); out-of-range values are clamped") @RequestParam(required = false) Integer limit,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(
                commentService.getCommentsByVote(voteId, cursor, limit, principal != null ? principal.userId() : null)));
    }

    @GetMapping("/{commentId}/replies")
    @PublicApiResponse
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success",
            content = @Content(examples = @ExampleObject(name = "replyPage", value = """
                            {
                              "success": true,
                              "data": {
                              "items": [
                                {
                                  "id": "0199c3b7-92f4-7a15-8d20-5b7c1e4f3a68",
                                  "voteId": "0199c2f0-4d3a-7e8b-b6c2-1a9f0e7d5c33",
                                  "userId": "0199b8e4-1c2d-7f60-8a3b-6d4e2f1a9b05",
                                  "author": "alice",
                                  "text": "I agree with you.",
                                  "createdAt": "2026-10-09T12:34:56+08:00",
                                  "updatedAt": "2026-10-09T12:34:56+08:00",
                                  "edited": false,
                                  "likeCount": 0,
                                  "likedByCurrentUser": false,
                                  "parentId": "0199c3a2-7b1e-7c4d-9a10-3f5e8d2b6a41",
                                  "replyCount": 0,
                                  "deleted": false
                                }
                              ],
                              "nextCursor": null
                            },
                              "message": null,
                              "errorCode": null,
                              "error": null
                            }
                            """)))
    @Operation(summary = "List Replies of a Comment",
            description = """
                    **Auth**: public (optional login changes response: `likedByCurrentUser` reflects the signed-in user's likes, and is always false when not logged in; nothing else differs)
                    **Precondition**: `commentId` is a top-level Comment of the Poll in the path. It may itself be a deleted placeholder; its Replies are still readable
                    **Behavior**: returns the non-deleted Replies, oldest first, cursor-paged (`limit` default 20, max 50). Replies are one level deep and `replyCount` is always 0 on them
                    **Side effects**: none
                    **Errors**:
                    - 400 `common.cursor_invalid`: `cursor` is not a token returned by this endpoint
                    - 404 `comment.not_found`: Comment does not exist, is a Reply rather than a top-level Comment, or belongs to another Poll
                    """)
    public ResponseEntity<ApiResponse<CursorResponse<CommentDto>>> getReplies(
            @Parameter(description = "Poll ID", required = true) @PathVariable String voteId,
            @Parameter(description = "Top-level Comment ID", required = true) @PathVariable UUID commentId,
            @Parameter(description = "`nextCursor` returned by the previous page; omit for the first page") @RequestParam(required = false) String cursor,
            @Parameter(description = "Page size, 1-50 (default 20); out-of-range values are clamped") @RequestParam(required = false) Integer limit,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(
                commentService.getReplies(voteId, commentId, cursor, limit, principal != null ? principal.userId() : null)));
    }

    @PutMapping("/{commentId}")
    @Auditable(action = "UPDATE", resourceType = "COMMENT")
    @CommonApiResponses
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success",
            content = @Content(examples = @ExampleObject(name = "edited", value = """
                            {
                              "success": true,
                              "data": {
                              "id": "0199c3a2-7b1e-7c4d-9a10-3f5e8d2b6a41",
                              "voteId": "0199c2f0-4d3a-7e8b-b6c2-1a9f0e7d5c33",
                              "userId": "0199b8e4-1c2d-7f60-8a3b-6d4e2f1a9b05",
                              "author": "alice",
                              "text": "Edited: B is cheaper and ships faster.",
                              "createdAt": "2026-10-09T12:34:56+08:00",
                              "updatedAt": "2026-10-09T12:40:02+08:00",
                              "edited": true,
                              "likeCount": 3,
                              "likedByCurrentUser": false,
                              "parentId": null,
                              "replyCount": 0,
                              "deleted": false
                            },
                              "message": null,
                              "errorCode": null,
                              "error": null
                            }
                            """)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Comment not found or already deleted (errorCode `comment.not_found`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(ref = "#/components/examples/" + OpenAPIConfig.EX_NOT_FOUND)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not the author (errorCode `comment.forbidden`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "notOwner", value = """
                            {
                              "success": false,
                              "data": null,
                              "message": "You can only modify your own comments",
                              "errorCode": "comment.forbidden",
                              "error": "You can only modify your own comments"
                            }
                            """)))
    @Operation(summary = "Edit own Comment or Reply",
            description = """
                    **Auth**: required
                    **Precondition**: the caller is the author and the Comment is not deleted. Works on both Comments and Replies, in any Poll state
                    **Behavior**: replaces the text. The Poll in the path is not checked; the Comment is looked up by `commentId` only
                    **Side effects**: `updatedAt` moves forward, so `edited` becomes true (it is derived: `updatedAt` after `createdAt`, and never true for a deleted placeholder). Likes are kept
                    **Errors**:
                    - 403 `comment.forbidden`: the caller is not the author
                    - 404 `comment.not_found`: Comment does not exist or is deleted
                    """,
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    examples = @ExampleObject(name = "edit", value = """
                            { "text": "Edited: B is cheaper and ships faster." }
                            """))))
    public ResponseEntity<ApiResponse<CommentDto>> updateComment(
            @Parameter(description = "Poll ID", required = true) @PathVariable String voteId,
            @Parameter(description = "Comment ID", required = true) @PathVariable UUID commentId,
            @Valid @RequestBody UpdateCommentRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        CommentDto response = commentService.updateComment(commentId, principal.userId(), request);
        log.info("Comment {} updated by user {}", commentId, principal.userId());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @DeleteMapping("/{commentId}")
    @Auditable(action = "DELETE", resourceType = "COMMENT")
    @CommonApiResponses
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success",
            content = @Content(examples = @ExampleObject(name = "deleted", value = """
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
                            }
                            """)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Comment not found or already deleted (errorCode `comment.not_found`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(ref = "#/components/examples/" + OpenAPIConfig.EX_NOT_FOUND)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not the author (errorCode `comment.forbidden`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "notOwner", value = """
                            {
                              "success": false,
                              "data": null,
                              "message": "You can only modify your own comments",
                              "errorCode": "comment.forbidden",
                              "error": "You can only modify your own comments"
                            }
                            """)))
    @Operation(summary = "Delete own Comment or Reply",
            description = """
                    **Auth**: required
                    **Precondition**: the caller is the author and the Comment is not already deleted
                    **Behavior**: soft delete. A deleted Reply disappears from the Replies list. A deleted top-level Comment with at least one non-deleted Reply stays in the Comment list as a placeholder (`deleted` = true; `userId`, `author` and `text` are null; `replyCount` and its Replies are kept). A deleted top-level Comment without Replies disappears from the list. Deleting twice is not idempotent: the second call is a 404
                    **Side effects**: the Comment can no longer be edited or liked. Responds with an empty success body (`{ "success": true }`)
                    **Errors**:
                    - 403 `comment.forbidden`: the caller is not the author
                    - 404 `comment.not_found`: Comment does not exist or is already deleted
                    """)
    public ResponseEntity<ApiResponse<SimpleResultResponse>> deleteComment(
            @Parameter(description = "Poll ID", required = true) @PathVariable String voteId,
            @Parameter(description = "Comment ID", required = true) @PathVariable UUID commentId,
            @AuthenticationPrincipal UserPrincipal principal) {
        commentService.deleteComment(commentId, principal.userId());
        log.info("Comment {} deleted by user {}", commentId, principal.userId());
        return ResponseEntity.ok(ApiResponse.ok(SimpleResultResponse.ok()));
    }

    @PostMapping("/{commentId}/like")
    @Auditable(action = "LIKE", resourceType = "COMMENT")
    @CommonApiResponses
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success",
            content = @Content(examples = @ExampleObject(name = "liked", value = """
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
                            }
                            """)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Comment or current user not found (errorCode `comment.not_found`, `user.not_found`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(ref = "#/components/examples/" + OpenAPIConfig.EX_NOT_FOUND)))
    @Operation(summary = "Like a Comment or Reply",
            description = """
                    **Auth**: required
                    **Precondition**: the Comment or Reply exists and is not deleted
                    **Behavior**: idempotent. Liking something already liked succeeds without change. Own Comments can be liked
                    **Side effects**: `likeCount` +1 and `likedByCurrentUser` true in later reads (first like only)
                    **Errors**:
                    - 404 `comment.not_found`: Comment does not exist or is deleted
                    - 404 `user.not_found`: the signed-in user no longer exists
                    """)
    public ResponseEntity<ApiResponse<SimpleResultResponse>> likeComment(
            @Parameter(description = "Poll ID", required = true) @PathVariable String voteId,
            @Parameter(description = "Comment ID", required = true) @PathVariable UUID commentId,
            @AuthenticationPrincipal UserPrincipal principal) {
        commentService.likeComment(commentId, principal.userId());
        log.info("Comment {} liked by user {}", commentId, principal.userId());
        return ResponseEntity.ok(ApiResponse.ok(SimpleResultResponse.ok()));
    }

    @DeleteMapping("/{commentId}/like")
    @Auditable(action = "UNLIKE", resourceType = "COMMENT")
    @CommonApiResponses
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success",
            content = @Content(examples = @ExampleObject(name = "unliked", value = """
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
                            }
                            """)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Comment not found or deleted (errorCode `comment.not_found`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(ref = "#/components/examples/" + OpenAPIConfig.EX_NOT_FOUND)))
    @Operation(summary = "Unlike a Comment or Reply",
            description = """
                    **Auth**: required
                    **Precondition**: the Comment or Reply exists and is not deleted
                    **Behavior**: idempotent. Removing a like that does not exist succeeds without change
                    **Side effects**: `likeCount` -1 and `likedByCurrentUser` false in later reads (only if a like existed)
                    **Errors**:
                    - 404 `comment.not_found`: Comment does not exist or is deleted
                    """)
    public ResponseEntity<ApiResponse<SimpleResultResponse>> unlikeComment(
            @Parameter(description = "Poll ID", required = true) @PathVariable String voteId,
            @Parameter(description = "Comment ID", required = true) @PathVariable UUID commentId,
            @AuthenticationPrincipal UserPrincipal principal) {
        commentService.unlikeComment(commentId, principal.userId());
        log.info("Comment {} unliked by user {}", commentId, principal.userId());
        return ResponseEntity.ok(ApiResponse.ok(SimpleResultResponse.ok()));
    }
}
