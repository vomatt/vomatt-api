package com.vomatt.admin;

import java.util.UUID;

import com.vomatt.admin.dto.AdminCommentResponse;
import com.vomatt.common.annotation.CommonApiResponses;
import com.vomatt.common.audit.Auditable;
import com.vomatt.common.config.OpenAPIConfig;
import com.vomatt.common.response.ApiResponse;
import com.vomatt.common.response.PageResponse;
import com.vomatt.common.response.SimpleResultResponse;
import com.vomatt.common.security.UserPrincipal;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/comments")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - Comment", description = "Comment moderation APIs (Admin)")
public class AdminCommentController {

    private final AdminCommentService adminCommentService;

    @GetMapping
    @CommonApiResponses
    @Operation(summary = "List Comments",
            description = """
                    **Auth**: admin (role ADMIN; 403 `common.forbidden` otherwise)
                    **Precondition**: caller holds role ADMIN
                    **Behavior**: offset page of Comments and Replies on any Poll (Deleted Polls included), newest first. Filters (all optional, combined with AND): `voteId`, `userId` (author), `q` (case-insensitive contains on the text), `deleted` true for deleted only, false for live only. Deleted Comments keep their author and text here. `page` is 1-based (default 1), `limit` 1-50 (default 20)
                    **Side effects**: none
                    **Errors**:
                    - 400 `common.type_mismatch`: `voteId`, `userId` or `deleted` is malformed""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Page of Comments",
            content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Comments", value = """
                    {
                      "success": true,
                      "data": {
                        "content": [
                          {
                            "id": "0199f3b1-2c4d-7e5f-8a9b-0c1d2e3f4a5b",
                            "voteId": "0199f2a3-5b7e-7d40-a1c8-9e3b2f6d4c05",
                            "voteTitle": "Which language should we use for the next side project?",
                            "parentId": null,
                            "userId": "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d47",
                            "username": "alice",
                            "content": "Java, obviously.",
                            "deleted": false,
                            "createdAt": "2026-10-16T09:30:00+08:00",
                            "updatedAt": "2026-10-16T09:30:00+08:00"
                          }
                        ],
                        "total": 1,
                        "page": 1,
                        "limit": 20
                      },
                      "message": null,
                      "errorCode": null,
                      "error": null
                    }""")))
    public ResponseEntity<ApiResponse<PageResponse<AdminCommentResponse>>> listComments(
            @Parameter(description = "Only Comments on this Poll (UUID)") @RequestParam(required = false) UUID voteId,
            @Parameter(description = "Only Comments by this user (UUID)") @RequestParam(required = false) UUID userId,
            @Parameter(description = "Text contained in the Comment", example = "spam") @RequestParam(required = false) String q,
            @Parameter(description = "true: deleted only; false: live only; omit for both", example = "false")
            @RequestParam(required = false) Boolean deleted,
            @Parameter(description = "Page number, 1-based (default 1)") @RequestParam(required = false) Integer page,
            @Parameter(description = "Page size (1-50, default 20)") @RequestParam(required = false) Integer limit) {
        return ResponseEntity.ok(ApiResponse.ok(adminCommentService.listComments(voteId, userId, q, deleted, page, limit)));
    }

    @DeleteMapping("/{commentId}")
    @Auditable(action = "FORCE_DELETE", resourceType = "COMMENT", resourceIdIndex = 0)
    @CommonApiResponses
    @Operation(summary = "Delete Comment",
            description = """
                    **Auth**: admin (role ADMIN; 403 `common.forbidden` otherwise)
                    **Precondition**: the Comment exists
                    **Behavior**: soft-deletes any Comment or Reply, as its author's own delete would; a deleted one is left as is (idempotent). Returns `{success: true}`
                    **Side effects**: the text and author disappear from the public Comment lists; a top-level Comment with live Replies stays there as a placeholder. Written to the audit log
                    **Errors**:
                    - 400 `common.type_mismatch`: `commentId` is not a valid UUID
                    - 404 `comment.not_found`: no Comment with this ID""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Comment deleted",
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
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Comment not found (`comment.not_found`)",
            content = @Content(mediaType = "application/json", schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "CommentNotFound", value = """
                            {
                              "success": false,
                              "data": null,
                              "message": "Comment not found",
                              "errorCode": "comment.not_found",
                              "error": "Comment not found"
                            }""")))
    public ResponseEntity<ApiResponse<SimpleResultResponse>> deleteComment(
            @Parameter(description = "Comment ID (UUIDv7)", required = true) @PathVariable UUID commentId,
            @AuthenticationPrincipal UserPrincipal principal) {
        adminCommentService.deleteComment(commentId, principal.userId());
        return ResponseEntity.ok(ApiResponse.ok(SimpleResultResponse.ok()));
    }
}
