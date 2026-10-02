package com.vomatt.comments;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.vomatt.comments.dto.CreateCommentRequest;
import com.vomatt.comments.dto.UpdateCommentRequest;
import com.vomatt.common.response.ApiResponse;
import com.vomatt.common.response.PageResponse;
import com.vomatt.common.response.SimpleResultResponse;
import com.vomatt.common.annotation.CommonApiResponses;
import com.vomatt.comments.dto.CommentDto;
import com.vomatt.common.security.UserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.vomatt.comments.VoteCommentService;
import com.vomatt.common.audit.Auditable;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
@RequestMapping("/api/votes/{voteId}/comments")
@RequiredArgsConstructor
@Tag(name = "Vote Comment", description = "Vote comment management APIs")
public class VoteCommentController {

    private static final Logger log = LoggerFactory.getLogger(VoteCommentController.class);
    private final VoteCommentService commentService;

    @PostMapping
    @Auditable(action = "CREATE", resourceType = "COMMENT")
    @CommonApiResponses
    @Operation(summary = "Create a comment", description = "Add a comment to a vote")
    public ResponseEntity<ApiResponse<CommentDto>> createComment(
            @Parameter(description = "Vote ID", required = true) @PathVariable String voteId,
            @Valid @RequestBody CreateCommentRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        CommentDto response = commentService.createComment(voteId, principal.userId(), request);
        log.info("Comment created on vote {} by user {}", voteId, principal.userId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(response));
    }

    @GetMapping
    @CommonApiResponses
    @Operation(summary = "Get comments for a vote", description = "Retrieve all comments for a vote (paginated)")
    public ResponseEntity<ApiResponse<PageResponse<CommentDto>>> getComments(
            @Parameter(description = "Vote ID", required = true) @PathVariable String voteId,
            @PageableDefault(size = 20) Pageable pageable,
            @AuthenticationPrincipal UserPrincipal principal) {
        Page<CommentDto> response = commentService.getCommentsByVote(voteId, pageable, principal.userId());
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.from(response)));
    }

    @PutMapping("/{commentId}")
    @Auditable(action = "UPDATE", resourceType = "COMMENT")
    @CommonApiResponses
    @Operation(summary = "Update a comment", description = "Update comment content (only by comment owner)")
    public ResponseEntity<ApiResponse<CommentDto>> updateComment(
            @Parameter(description = "Vote ID", required = true) @PathVariable String voteId,
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
    @Operation(summary = "Delete a comment", description = "Delete a comment (soft delete, only by comment owner)")
    public ResponseEntity<ApiResponse<SimpleResultResponse>> deleteComment(
            @Parameter(description = "Vote ID", required = true) @PathVariable String voteId,
            @Parameter(description = "Comment ID", required = true) @PathVariable UUID commentId,
            @AuthenticationPrincipal UserPrincipal principal) {
        commentService.deleteComment(commentId, principal.userId());
        log.info("Comment {} deleted by user {}", commentId, principal.userId());
        return ResponseEntity.ok(ApiResponse.ok(SimpleResultResponse.ok()));
    }

    @PostMapping("/{commentId}/like")
    @Auditable(action = "LIKE", resourceType = "COMMENT")
    @CommonApiResponses
    @Operation(summary = "Like a comment", description = "Add a like to a comment")
    public ResponseEntity<ApiResponse<SimpleResultResponse>> likeComment(
            @Parameter(description = "Vote ID", required = true) @PathVariable String voteId,
            @Parameter(description = "Comment ID", required = true) @PathVariable UUID commentId,
            @AuthenticationPrincipal UserPrincipal principal) {
        commentService.likeComment(commentId, principal.userId());
        log.info("Comment {} liked by user {}", commentId, principal.userId());
        return ResponseEntity.ok(ApiResponse.ok(SimpleResultResponse.ok()));
    }

    @DeleteMapping("/{commentId}/like")
    @Auditable(action = "UNLIKE", resourceType = "COMMENT")
    @CommonApiResponses
    @Operation(summary = "Unlike a comment", description = "Remove a like from a comment")
    public ResponseEntity<ApiResponse<SimpleResultResponse>> unlikeComment(
            @Parameter(description = "Vote ID", required = true) @PathVariable String voteId,
            @Parameter(description = "Comment ID", required = true) @PathVariable UUID commentId,
            @AuthenticationPrincipal UserPrincipal principal) {
        commentService.unlikeComment(commentId, principal.userId());
        log.info("Comment {} unliked by user {}", commentId, principal.userId());
        return ResponseEntity.ok(ApiResponse.ok(SimpleResultResponse.ok()));
    }
}
