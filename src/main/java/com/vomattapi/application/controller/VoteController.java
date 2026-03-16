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
import com.vomattapi.application.dto.request.CreateVoteRequest;
import com.vomattapi.application.dto.request.UpdateCommentRequest;
import com.vomattapi.application.dto.request.VoteRequest;
import com.vomattapi.application.dto.response.ApiResponse;
import com.vomattapi.application.dto.response.CommentDto;
import com.vomattapi.application.dto.response.ErrorType;
import com.vomattapi.application.dto.response.MessageResponse;
import com.vomattapi.application.dto.response.UserVoteStatusResponse;
import com.vomattapi.application.dto.response.VoteResponse;
import com.vomattapi.application.dto.response.VoteResultResponse;
import com.vomattapi.application.security.services.UserDetailsImpl;
import com.vomattapi.application.service.VoteCommentService;
import com.vomattapi.application.service.VoteService;
import com.vomattapi.infrastructure.audit.Auditable;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
@RequestMapping("/api/v1/votes")
@RequiredArgsConstructor
@Tag(name = "Vote", description = "Vote management APIs")
@SecurityRequirement(name = "Bearer Authentication")
public class VoteController {
    private static final Logger log = LoggerFactory.getLogger(VoteController.class);
    private final VoteService voteService;
    private final VoteCommentService commentService;

    @PostMapping
    @PreAuthorize("hasRole('USER') or hasRole('MODERATOR') or hasRole('ADMIN')")
    @Auditable(action = "CREATE", resourceType = "VOTE")
    @Operation(summary = "Create a new vote", description = "Create a new vote with multiple options")
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Vote created successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid request"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden")
    })
    public ResponseEntity<ApiResponse<VoteResponse>> createVote(
            @Valid @RequestBody CreateVoteRequest request,
            Authentication authentication) {
        try {
            UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();
            VoteResponse voteResponse = voteService.createVote(request, userDetails.getId());
            log.info("Vote created: {} by user: {}", voteResponse.getId(), userDetails.getUsername());
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ApiResponse.success(voteResponse, "Vote created successfully"));
        } catch (Exception e) {
            log.error("Failed to create vote", e);
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(ErrorType.BUSINESS_RULE_VIOLATION, e.getMessage()));
        }
    }

    @GetMapping("/{voteId}")
    @Operation(summary = "Get vote by ID", description = "Retrieve vote details by vote ID")
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Vote retrieved successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Vote not found")
    })
    public ResponseEntity<ApiResponse<VoteResponse>> getVote(
            @Parameter(description = "Vote ID", required = true)
            @PathVariable String voteId) {
        try {
            VoteResponse response = voteService.getVote(voteId);
            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (Exception e) {
            log.error("Failed to get vote: {}", voteId, e);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(ErrorType.VOTE_NOT_FOUND, e.getMessage()));
        }
    }

    @GetMapping
    @Operation(summary = "Get active votes", description = "Retrieve all active votes with pagination")
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Active votes retrieved successfully")
    })
    public ResponseEntity<ApiResponse<Page<VoteResponse>>> getActiveVotes(
            @PageableDefault(size = 20) Pageable pageable) {
        try {
            Page<VoteResponse> response = voteService.getActiveVotes(pageable);
            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (Exception e) {
            log.error("Failed to get active votes", e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error(ErrorType.INTERNAL_ERROR));
        }
    }

    @GetMapping("/my")
    @PreAuthorize("hasRole('USER') or hasRole('MODERATOR') or hasRole('ADMIN')")
    @Operation(summary = "Get user's votes", description = "Retrieve votes created by the authenticated user (paginated)")
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "User votes retrieved successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden")
    })
    public ResponseEntity<ApiResponse<Page<VoteResponse>>> getMyVotes(
            @PageableDefault(size = 20) Pageable pageable,
            Authentication authentication) {
        try {
            UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();
            Page<VoteResponse> response = voteService.getVotesByCreator(userDetails.getId(), pageable);
            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (Exception e) {
            log.error("Failed to get user votes", e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error(ErrorType.INTERNAL_ERROR));
        }
    }

    @PostMapping("/{voteId}/vote")
    @PreAuthorize("hasRole('USER') or hasRole('MODERATOR') or hasRole('ADMIN')")
    @Auditable(action = "VOTE", resourceType = "VOTE", resourceIdIndex = 0)
    @Operation(summary = "Vote on a poll", description = "Cast vote(s) on the specified vote")
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Vote cast successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid vote request"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Vote not found")
    })
    public ResponseEntity<ApiResponse<VoteResponse>> vote(
            @Parameter(description = "Vote ID", required = true)
            @PathVariable String voteId,
            @Valid @RequestBody VoteRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest) {
        try {
            UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();
            String ipAddress = getClientIpAddress(httpRequest);
            VoteResponse response = voteService.vote(voteId, request, userDetails.getId(), ipAddress);
            log.info("User {} voted on vote {}", userDetails.getUsername(), voteId);
            return ResponseEntity.ok(ApiResponse.success(response, "Vote cast successfully"));
        } catch (Exception e) {
            log.error("Failed to cast vote", e);
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(ErrorType.VOTING_NOT_ALLOWED, e.getMessage()));
        }
    }

    @DeleteMapping("/{voteId}/vote/{optionId}")
    @PreAuthorize("hasRole('USER') or hasRole('MODERATOR') or hasRole('ADMIN')")
    @Operation(summary = "Remove vote", description = "Remove vote from a specific option")
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Vote removed successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Vote or option not found")
    })
    public ResponseEntity<ApiResponse<VoteResponse>> removeVote(
            @Parameter(description = "Vote ID", required = true)
            @PathVariable String voteId,
            @Parameter(description = "Option ID", required = true)
            @PathVariable String optionId,
            Authentication authentication) {
        try {
            UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();
            VoteResponse response = voteService.removeVote(voteId, optionId, userDetails.getId());
            log.info("User {} removed vote from option {} in vote {}",
                    userDetails.getUsername(), optionId, voteId);
            return ResponseEntity.ok(ApiResponse.success(response, "Vote removed successfully"));
        } catch (Exception e) {
            log.error("Failed to remove vote", e);
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(ErrorType.VOTING_NOT_ALLOWED, e.getMessage()));
        }
    }

    @GetMapping("/{voteId}/results")
    @Operation(summary = "Get vote results", description = "Retrieve detailed vote results including voter information (if not anonymous)")
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Vote results retrieved successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Vote not found")
    })
    public ResponseEntity<ApiResponse<VoteResultResponse>> getVoteResults(
            @Parameter(description = "Vote ID", required = true)
            @PathVariable String voteId) {
        try {
            VoteResultResponse response = voteService.getVoteResults(voteId);
            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (Exception e) {
            log.error("Failed to get vote results: {}", voteId, e);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(ErrorType.VOTE_NOT_FOUND, e.getMessage()));
        }
    }

    @GetMapping("/{voteId}/my-vote-status")
    @PreAuthorize("hasRole('USER') or hasRole('MODERATOR') or hasRole('ADMIN')")
    @Operation(summary = "Check user vote status", description = "Check if the authenticated user has voted and which options they selected")
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Vote status retrieved successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Vote not found")
    })
    public ResponseEntity<ApiResponse<UserVoteStatusResponse>> getMyVoteStatus(
            @Parameter(description = "Vote ID", required = true)
            @PathVariable String voteId,
            Authentication authentication) {
        try {
            UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();

            UserVoteStatusResponse response = new UserVoteStatusResponse();
            response.setHasVoted(voteService.hasUserVoted(voteId, userDetails.getId()));
            response.setSelectedOptions(voteService.getUserVoteOptions(voteId, userDetails.getId()));

            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (Exception e) {
            log.error("Failed to get vote status: {}", voteId, e);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(ErrorType.VOTE_NOT_FOUND, e.getMessage()));
        }
    }

    @PutMapping("/{voteId}/deactivate")
    @PreAuthorize("hasRole('USER') or hasRole('MODERATOR') or hasRole('ADMIN')")
    @Auditable(action = "DEACTIVATE", resourceType = "VOTE", resourceIdIndex = 0)
    @Operation(summary = "Deactivate vote", description = "Deactivate a vote (only by creator)")
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Vote deactivated successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden - Only creator can deactivate"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Vote not found")
    })
    public ResponseEntity<ApiResponse<Void>> deactivateVote(
            @Parameter(description = "Vote ID", required = true)
            @PathVariable String voteId,
            Authentication authentication) {
        try {
            UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();
            voteService.deactivateVote(voteId, userDetails.getId());
            log.info("Vote {} deactivated by creator {}", voteId, userDetails.getUsername());
            return ResponseEntity.ok(ApiResponse.success("Vote deactivated successfully"));
        } catch (Exception e) {
            log.error("Failed to deactivate vote: {}", voteId, e);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error(ErrorType.UNAUTHORIZED_OPERATION, e.getMessage()));
        }
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

    // ========== Comment Endpoints ==========


    @PostMapping("/{voteId}/comments")
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
            @Parameter(description = "Vote ID", required = true)
            @PathVariable String voteId,
            @Valid @RequestBody CreateCommentRequest request,
            Authentication authentication) {
        try {
            UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();
            CommentDto response = commentService.createComment(voteId, userDetails.getId(), request);
            log.info("Comment created on vote {} by user {}", voteId, userDetails.getUsername());
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ApiResponse.success(response, "Comment created successfully"));
        } catch (Exception e) {
            log.error("Failed to create comment on vote: {}", voteId, e);
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(ErrorType.BUSINESS_RULE_VIOLATION, e.getMessage()));
        }
    }

    @GetMapping("/{voteId}/comments")
    @Operation(summary = "Get comments for a vote", description = "Retrieve all comments for a vote (paginated)")
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Comments retrieved successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Vote not found")
    })
    public ResponseEntity<ApiResponse<Page<CommentDto>>> getComments(
            @Parameter(description = "Vote ID", required = true)
            @PathVariable String voteId,
            @PageableDefault(size = 20) Pageable pageable,
            Authentication authentication) {
        try {
            String currentUserId = null;
            if (authentication != null && authentication.getPrincipal() instanceof UserDetailsImpl) {
                UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();
                currentUserId = userDetails.getId();
            }

            Page<CommentDto> response = commentService.getCommentsByVote(voteId, pageable, currentUserId);
            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (Exception e) {
            log.error("Failed to get comments for vote: {}", voteId, e);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(ErrorType.VOTE_NOT_FOUND, e.getMessage()));
        }
    }

    @PutMapping("/{voteId}/comments/{commentId}")
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
            @Parameter(description = "Vote ID", required = true)
            @PathVariable String voteId,
            @Parameter(description = "Comment ID", required = true)
            @PathVariable UUID commentId,
            @Valid @RequestBody UpdateCommentRequest request,
            Authentication authentication) {
        try {
            UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();
            CommentDto response = commentService.updateComment(commentId, userDetails.getId(), request);
            log.info("Comment {} updated by user {}", commentId, userDetails.getUsername());
            return ResponseEntity.ok(ApiResponse.success(response, "Comment updated successfully"));
        } catch (Exception e) {
            log.error("Failed to update comment: {}", commentId, e);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error(ErrorType.UNAUTHORIZED_OPERATION, e.getMessage()));
        }
    }

    @DeleteMapping("/{voteId}/comments/{commentId}")
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
            @Parameter(description = "Vote ID", required = true)
            @PathVariable String voteId,
            @Parameter(description = "Comment ID", required = true)
            @PathVariable UUID commentId,
            Authentication authentication) {
        try {
            UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();
            commentService.deleteComment(commentId, userDetails.getId());
            log.info("Comment {} deleted by user {}", commentId, userDetails.getUsername());
            return ResponseEntity.ok(ApiResponse.success("Comment deleted successfully"));
        } catch (Exception e) {
            log.error("Failed to delete comment: {}", commentId, e);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error(ErrorType.UNAUTHORIZED_OPERATION, e.getMessage()));
        }
    }

    @PostMapping("/{voteId}/comments/{commentId}/like")
    @PreAuthorize("hasRole('USER') or hasRole('MODERATOR') or hasRole('ADMIN')")
    @Auditable(action = "LIKE", resourceType = "COMMENT")
    @Operation(summary = "Like a comment", description = "Add a like to a comment")
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Comment liked successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Comment not found")
    })
    public ResponseEntity<ApiResponse<Void>> likeComment(
            @Parameter(description = "Vote ID", required = true)
            @PathVariable String voteId,
            @Parameter(description = "Comment ID", required = true)
            @PathVariable UUID commentId,
            Authentication authentication) {
        try {
            UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();
            commentService.likeComment(commentId, userDetails.getId());
            log.info("Comment {} liked by user {}", commentId, userDetails.getUsername());
            return ResponseEntity.ok(ApiResponse.success("Comment liked successfully"));
        } catch (Exception e) {
            log.error("Failed to like comment: {}", commentId, e);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(ErrorType.BUSINESS_RULE_VIOLATION, e.getMessage()));
        }
    }

    @DeleteMapping("/{voteId}/comments/{commentId}/like")
    @PreAuthorize("hasRole('USER') or hasRole('MODERATOR') or hasRole('ADMIN')")
    @Auditable(action = "UNLIKE", resourceType = "COMMENT")
    @Operation(summary = "Unlike a comment", description = "Remove a like from a comment")
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Comment unliked successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Comment not found")
    })
    public ResponseEntity<ApiResponse<Void>> unlikeComment(
            @Parameter(description = "Vote ID", required = true)
            @PathVariable String voteId,
            @Parameter(description = "Comment ID", required = true)
            @PathVariable UUID commentId,
            Authentication authentication) {
        try {
            UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();
            commentService.unlikeComment(commentId, userDetails.getId());
            log.info("Comment {} unliked by user {}", commentId, userDetails.getUsername());
            return ResponseEntity.ok(ApiResponse.success("Comment unliked successfully"));
        } catch (Exception e) {
            log.error("Failed to unlike comment: {}", commentId, e);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(ErrorType.BUSINESS_RULE_VIOLATION, e.getMessage()));
        }
    }
}