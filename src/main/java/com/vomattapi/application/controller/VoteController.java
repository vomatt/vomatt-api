package com.vomattapi.application.controller;

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

import com.vomattapi.application.dto.request.CreateVoteRequest;
import com.vomattapi.application.dto.request.VoteRequest;
import com.vomattapi.application.dto.response.ApiResponse;
import com.vomattapi.application.dto.response.UserVoteStatusResponse;
import com.vomattapi.application.dto.response.VoteResponse;
import com.vomattapi.application.dto.response.VoteResultResponse;
import com.vomattapi.application.security.services.UserDetailsImpl;
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
        UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();
        VoteResponse voteResponse = voteService.createVote(request, userDetails.getId());
        log.info("Vote created: {} by user: {}", voteResponse.getId(), userDetails.getUsername());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(voteResponse, "Vote created successfully"));
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
        VoteResponse response = voteService.getVote(voteId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping
    @Operation(summary = "Get active votes", description = "Retrieve all active votes with pagination")
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Active votes retrieved successfully")
    })
    public ResponseEntity<ApiResponse<Page<VoteResponse>>> getActiveVotes(
            @PageableDefault(size = 20) Pageable pageable) {
        Page<VoteResponse> response = voteService.getActiveVotes(pageable);
        return ResponseEntity.ok(ApiResponse.success(response));
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
        UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();
        Page<VoteResponse> response = voteService.getVotesByCreator(userDetails.getId(), pageable);
        return ResponseEntity.ok(ApiResponse.success(response));
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
        UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();
        String ipAddress = getClientIpAddress(httpRequest);
        VoteResponse response = voteService.vote(voteId, request, userDetails.getId(), ipAddress);
        log.info("User {} voted on vote {}", userDetails.getUsername(), voteId);
        return ResponseEntity.ok(ApiResponse.success(response, "Vote cast successfully"));
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
        UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();
        VoteResponse response = voteService.removeVote(voteId, optionId, userDetails.getId());
        log.info("User {} removed vote from option {} in vote {}",
                userDetails.getUsername(), optionId, voteId);
        return ResponseEntity.ok(ApiResponse.success(response, "Vote removed successfully"));
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
        VoteResultResponse response = voteService.getVoteResults(voteId);
        return ResponseEntity.ok(ApiResponse.success(response));
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
        UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();
        UserVoteStatusResponse response = new UserVoteStatusResponse();
        response.setHasVoted(voteService.hasUserVoted(voteId, userDetails.getId()));
        response.setSelectedOptions(voteService.getUserVoteOptions(voteId, userDetails.getId()));
        return ResponseEntity.ok(ApiResponse.success(response));
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
        UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();
        voteService.deactivateVote(voteId, userDetails.getId());
        log.info("Vote {} deactivated by creator {}", voteId, userDetails.getUsername());
        return ResponseEntity.ok(ApiResponse.success("Vote deactivated successfully"));
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
