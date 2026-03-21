package com.vomattapi.application.controller;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.vomattapi.application.dto.request.CreateCommentRequest;
import com.vomattapi.application.dto.request.UpdateCommentRequest;
import com.vomattapi.application.dto.response.ApiResponse;
import com.vomattapi.application.dto.response.CommentDto;
import com.vomattapi.application.security.services.UserDetailsImpl;
import com.vomattapi.application.service.VoteCommentService;
import com.vomattapi.infrastructure.audit.Auditable;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
@RequestMapping("/api/v1/votes/{voteId}/comments")
@RequiredArgsConstructor
@Tag(name = "Vote Comment", description = "Vote comment management APIs")
@SecurityRequirement(name = "Bearer Authentication")
public class VoteCommentController {

    private static final Logger log = LoggerFactory.getLogger(VoteCommentController.class);
    private final VoteCommentService commentService;

    @PostMapping
    @PreAuthorize("hasRole('USER') or hasRole('MODERATOR') or hasRole('ADMIN')")
    @Auditable(action = "CREATE", resourceType = "COMMENT")
    @Operation(summary = "Create a comment", description = "Add a comment to a vote")
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Comment created successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid request"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Vote not found")
    })
    public ResponseEntity<ApiResponse<CommentDto>> createComment(
            @Parameter(description = "Vote ID", required = true) @PathVariable String voteId,
            @Valid @RequestBody CreateCommentRequest request,
            Authentication authentication) {
        UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();
        CommentDto response = commentService.createComment(voteId, userDetails.getId(), request);
        log.info("Comment created on vote {} by user {}", voteId, userDetails.getUsername());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response, "Comment created successfully"));
    }

    @GetMapping
    @Operation(summary = "Get comments for a vote", description = "Retrieve all comments for a vote (paginated)")
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Comments retrieved successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Vote not found")
    })
    public ResponseEntity<ApiResponse<Page<CommentDto>>> getComments(
            @Parameter(description = "Vote ID", required = true) @PathVariable String voteId,
            @PageableDefault(size = 20) Pageable pageable,
            Authentication authentication) {
        String currentUserId = null;
        if (authentication != null && authentication.getPrincipal() instanceof UserDetailsImpl userDetails) {
            currentUserId = userDetails.getId();
        }
        Page<CommentDto> response = commentService.getCommentsByVote(voteId, pageable, currentUserId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PutMapping("/{commentId}")
    @PreAuthorize("hasRole('USER') or hasRole('MODERATOR') or hasRole('ADMIN')")
    @Auditable(action = "UPDATE", resourceType = "COMMENT")
    @Operation(summary = "Update a comment", description = "Update comment content (only by comment owner)")
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Comment updated successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden - Only comment owner can update"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Comment not found")
    })
    public ResponseEntity<ApiResponse<CommentDto>> updateComment(
            @Parameter(description = "Vote ID", required = true) @PathVariable String voteId,
            @Parameter(description = "Comment ID", required = true) @PathVariable UUID commentId,
            @Valid @RequestBody UpdateCommentRequest request,
            Authentication authentication) {
        UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();
        CommentDto response = commentService.updateComment(commentId, userDetails.getId(), request);
        log.info("Comment {} updated by user {}", commentId, userDetails.getUsername());
        return ResponseEntity.ok(ApiResponse.success(response, "Comment updated successfully"));
    }

    @DeleteMapping("/{commentId}")
    @PreAuthorize("hasRole('USER') or hasRole('MODERATOR') or hasRole('ADMIN')")
    @Auditable(action = "DELETE", resourceType = "COMMENT")
    @Operation(summary = "Delete a comment", description = "Delete a comment (soft delete, only by comment owner)")
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Comment deleted successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden - Only comment owner can delete"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Comment not found")
    })
    public ResponseEntity<ApiResponse<Void>> deleteComment(
            @Parameter(description = "Vote ID", required = true) @PathVariable String voteId,
            @Parameter(description = "Comment ID", required = true) @PathVariable UUID commentId,
            Authentication authentication) {
        UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();
        commentService.deleteComment(commentId, userDetails.getId());
        log.info("Comment {} deleted by user {}", commentId, userDetails.getUsername());
        return ResponseEntity.ok(ApiResponse.success("Comment deleted successfully"));
    }

    @PostMapping("/{commentId}/like")
    @PreAuthorize("hasRole('USER') or hasRole('MODERATOR') or hasRole('ADMIN')")
    @Auditable(action = "LIKE", resourceType = "COMMENT")
    @Operation(summary = "Like a comment", description = "Add a like to a comment")
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Comment liked successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Comment not found")
    })
    public ResponseEntity<ApiResponse<Void>> likeComment(
            @Parameter(description = "Vote ID", required = true) @PathVariable String voteId,
            @Parameter(description = "Comment ID", required = true) @PathVariable UUID commentId,
            Authentication authentication) {
        UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();
        commentService.likeComment(commentId, userDetails.getId());
        log.info("Comment {} liked by user {}", commentId, userDetails.getUsername());
        return ResponseEntity.ok(ApiResponse.success("Comment liked successfully"));
    }

    @DeleteMapping("/{commentId}/like")
    @PreAuthorize("hasRole('USER') or hasRole('MODERATOR') or hasRole('ADMIN')")
    @Auditable(action = "UNLIKE", resourceType = "COMMENT")
    @Operation(summary = "Unlike a comment", description = "Remove a like from a comment")
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Comment unliked successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Comment not found")
    })
    public ResponseEntity<ApiResponse<Void>> unlikeComment(
            @Parameter(description = "Vote ID", required = true) @PathVariable String voteId,
            @Parameter(description = "Comment ID", required = true) @PathVariable UUID commentId,
            Authentication authentication) {
        UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();
        commentService.unlikeComment(commentId, userDetails.getId());
        log.info("Comment {} unliked by user {}", commentId, userDetails.getUsername());
        return ResponseEntity.ok(ApiResponse.success("Comment unliked successfully"));
    }
}
