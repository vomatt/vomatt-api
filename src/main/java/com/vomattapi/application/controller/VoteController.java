package com.vomattapi.application.controller;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
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
import com.vomattapi.application.dto.response.MessageResponse;
import com.vomattapi.application.dto.response.VoteResponse;
import com.vomattapi.application.dto.response.VoteResultResponse;
import com.vomattapi.application.security.services.MemberDetailsImpl;
import com.vomattapi.application.service.VoteService;
import com.vomattapi.infrastructure.audit.Auditable;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api/v1/votes")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Vote", description = "Vote management APIs")
@SecurityRequirement(name = "Bearer Authentication")
public class VoteController {

    private final VoteService voteService;

    @PostMapping
    @PreAuthorize("hasRole('USER') or hasRole('MODERATOR') or hasRole('ADMIN')")
    @Auditable(action = "CREATE", resourceType = "VOTE")
    @Operation(summary = "Create a new vote", description = "Create a new vote with multiple options")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Vote created successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid request"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "403", description = "Forbidden")
    })
    public ResponseEntity<VoteResponse> createVote(
            @Valid @RequestBody CreateVoteRequest request,
            Authentication authentication) {
        MemberDetailsImpl memberDetails = (MemberDetailsImpl) authentication.getPrincipal();
        VoteResponse response = voteService.createVote(request, memberDetails.getId());
        log.info("Vote created: {} by user: {}", response.getId(), memberDetails.getUsername());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{voteId}")
    @Operation(summary = "Get vote by ID", description = "Retrieve vote details by vote ID")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Vote retrieved successfully"),
        @ApiResponse(responseCode = "404", description = "Vote not found")
    })
    public ResponseEntity<VoteResponse> getVote(
            @Parameter(description = "Vote ID", required = true) 
            @PathVariable String voteId) {
        VoteResponse response = voteService.getVote(voteId);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    @Operation(summary = "Get active votes", description = "Retrieve all active votes with pagination")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Active votes retrieved successfully")
    })
    public ResponseEntity<Page<VoteResponse>> getActiveVotes(
            @PageableDefault(size = 20) Pageable pageable) {
        Page<VoteResponse> response = voteService.getActiveVotes(pageable);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/my-votes")
    @PreAuthorize("hasRole('USER') or hasRole('MODERATOR') or hasRole('ADMIN')")
    @Operation(summary = "Get user's votes", description = "Retrieve votes created by the authenticated user")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "User votes retrieved successfully"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "403", description = "Forbidden")
    })
    public ResponseEntity<List<VoteResponse>> getMyVotes(Authentication authentication) {
        MemberDetailsImpl memberDetails = (MemberDetailsImpl) authentication.getPrincipal();
        List<VoteResponse> response = voteService.getVotesByCreator(memberDetails.getId());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{voteId}/vote")
    @PreAuthorize("hasRole('USER') or hasRole('MODERATOR') or hasRole('ADMIN')")
    @Auditable(action = "VOTE", resourceType = "VOTE", resourceIdIndex = 0)
    @Operation(summary = "Vote on a poll", description = "Cast vote(s) on the specified vote")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Vote cast successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid vote request"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "403", description = "Forbidden"),
        @ApiResponse(responseCode = "404", description = "Vote not found")
    })
    public ResponseEntity<VoteResponse> vote(
            @Parameter(description = "Vote ID", required = true) 
            @PathVariable String voteId,
            @Valid @RequestBody VoteRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest) {
        MemberDetailsImpl memberDetails = (MemberDetailsImpl) authentication.getPrincipal();
        String ipAddress = getClientIpAddress(httpRequest);
        VoteResponse response = voteService.vote(voteId, request, memberDetails.getId(), ipAddress);
        log.info("Member {} voted on vote {}", memberDetails.getUsername(), voteId);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{voteId}/vote/{optionId}")
    @PreAuthorize("hasRole('USER') or hasRole('MODERATOR') or hasRole('ADMIN')")
    @Operation(summary = "Remove vote", description = "Remove vote from a specific option")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Vote removed successfully"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "403", description = "Forbidden"),
        @ApiResponse(responseCode = "404", description = "Vote or option not found")
    })
    public ResponseEntity<VoteResponse> removeVote(
            @Parameter(description = "Vote ID", required = true) 
            @PathVariable String voteId,
            @Parameter(description = "Option ID", required = true) 
            @PathVariable String optionId,
            Authentication authentication) {
        MemberDetailsImpl memberDetails = (MemberDetailsImpl) authentication.getPrincipal();
        VoteResponse response = voteService.removeVote(voteId, optionId, memberDetails.getId());
        log.info("Member {} removed vote from option {} in vote {}", 
                memberDetails.getUsername(), optionId, voteId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{voteId}/results")
    @Operation(summary = "Get vote results", description = "Retrieve detailed vote results including voter information (if not anonymous)")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Vote results retrieved successfully"),
        @ApiResponse(responseCode = "404", description = "Vote not found")
    })
    public ResponseEntity<VoteResultResponse> getVoteResults(
            @Parameter(description = "Vote ID", required = true) 
            @PathVariable String voteId) {
        VoteResultResponse response = voteService.getVoteResults(voteId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{voteId}/my-vote-status")
    @PreAuthorize("hasRole('USER') or hasRole('MODERATOR') or hasRole('ADMIN')")
    @Operation(summary = "Check user vote status", description = "Check if the authenticated user has voted and which options they selected")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Vote status retrieved successfully"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "403", description = "Forbidden"),
        @ApiResponse(responseCode = "404", description = "Vote not found")
    })
    public ResponseEntity<UserVoteStatusResponse> getMyVoteStatus(
            @Parameter(description = "Vote ID", required = true) 
            @PathVariable String voteId,
            Authentication authentication) {
        MemberDetailsImpl memberDetails = (MemberDetailsImpl) authentication.getPrincipal();
        boolean hasVoted = voteService.hasUserVoted(voteId, memberDetails.getId());
        List<String> selectedOptions = voteService.getUserVoteOptions(voteId, memberDetails.getId());
        
        UserVoteStatusResponse response = new UserVoteStatusResponse();
        response.setHasVoted(hasVoted);
        response.setSelectedOptions(selectedOptions);
        
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{voteId}/deactivate")
    @PreAuthorize("hasRole('USER') or hasRole('MODERATOR') or hasRole('ADMIN')")
    @Auditable(action = "DEACTIVATE", resourceType = "VOTE", resourceIdIndex = 0)
    @Operation(summary = "Deactivate vote", description = "Deactivate a vote (only by creator)")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Vote deactivated successfully"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "403", description = "Forbidden - Only creator can deactivate"),
        @ApiResponse(responseCode = "404", description = "Vote not found")
    })
    public ResponseEntity<MessageResponse> deactivateVote(
            @Parameter(description = "Vote ID", required = true) 
            @PathVariable String voteId,
            Authentication authentication) {
        MemberDetailsImpl memberDetails = (MemberDetailsImpl) authentication.getPrincipal();
        voteService.deactivateVote(voteId, memberDetails.getId());
        log.info("Vote {} deactivated by creator {}", voteId, memberDetails.getUsername());
        return ResponseEntity.ok(new MessageResponse("Vote deactivated successfully"));
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

    public static class UserVoteStatusResponse {
        private boolean hasVoted;
        private List<String> selectedOptions;
        
        public boolean isHasVoted() { return hasVoted; }
        public void setHasVoted(boolean hasVoted) { this.hasVoted = hasVoted; }
        public List<String> getSelectedOptions() { return selectedOptions; }
        public void setSelectedOptions(List<String> selectedOptions) { this.selectedOptions = selectedOptions; }
    }
}