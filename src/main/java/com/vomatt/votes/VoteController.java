package com.vomatt.votes;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
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
import com.vomatt.common.response.PageResponse;
import com.vomatt.common.response.SimpleResultResponse;
import com.vomatt.common.annotation.CommonApiResponses;
import com.vomatt.common.annotation.PublicApiResponse;
import com.vomatt.votes.dto.UserVoteStatusResponse;
import com.vomatt.votes.dto.VoteResponse;
import com.vomatt.votes.dto.VoteResultResponse;
import com.vomatt.common.security.UserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.vomatt.votes.VoteService;
import com.vomatt.common.audit.Auditable;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
@RequestMapping("/api/votes")
@RequiredArgsConstructor
@Tag(name = "Vote", description = "Vote management APIs")
public class VoteController {
    private static final Logger log = LoggerFactory.getLogger(VoteController.class);
    private final VoteService voteService;

    @PostMapping
    @Auditable(action = "CREATE", resourceType = "VOTE")
    @CommonApiResponses
    @Operation(summary = "Create a new vote", description = "Create a new vote with multiple options")
    public ResponseEntity<ApiResponse<VoteResponse>> createVote(
            @Valid @RequestBody CreateVoteRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        VoteResponse voteResponse = voteService.createVote(request, principal.userId());
        log.info("Vote created: {} by user: {}", voteResponse.getId(), principal.userId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(voteResponse));
    }

    @GetMapping("/{voteId}")
    @PublicApiResponse
    @Operation(summary = "Get vote by ID",
            description = "Public. Option counts are withheld until the vote ends; signed-in viewers also get myOptionId")
    public ResponseEntity<ApiResponse<VoteResponse>> getVote(
            @Parameter(description = "Vote ID", required = true)
            @PathVariable String voteId,
            @AuthenticationPrincipal UserPrincipal principal) {
        VoteResponse response = voteService.getVote(voteId, viewerId(principal));
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping
    @PublicApiResponse
    @Operation(summary = "Get active votes",
            description = "Open votes, newest first. ?tag= filters by topic; ?creatorUsername= lists one user's votes, ended ones included")
    public ResponseEntity<ApiResponse<PageResponse<VoteResponse>>> getActiveVotes(
            @PageableDefault(size = 20) Pageable pageable,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String creatorUsername,
            @AuthenticationPrincipal UserPrincipal principal) {
        String viewerId = viewerId(principal);
        Page<VoteResponse> votes;
        if (creatorUsername != null && !creatorUsername.isBlank()) {
            votes = voteService.getVotesByCreatorUsername(creatorUsername, pageable, viewerId);
        } else if (tag != null && !tag.isBlank()) {
            votes = voteService.getActiveVotesByTag(tag, pageable, viewerId);
        } else {
            votes = voteService.getActiveVotes(pageable, viewerId);
        }
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.from(votes)));
    }

    @GetMapping("/participated")
    @CommonApiResponses
    @Operation(summary = "Get votes I voted in", description = "Votes the authenticated user holds a ballot in, each with myOptionId (paginated)")
    public ResponseEntity<ApiResponse<PageResponse<VoteResponse>>> getParticipatedVotes(
            @PageableDefault(size = 20) Pageable pageable,
            @AuthenticationPrincipal UserPrincipal principal) {
        Page<VoteResponse> response = voteService.getParticipatedVotes(principal.userId(), pageable);
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.from(response)));
    }

    @PutMapping("/{voteId}")
    @Auditable(action = "UPDATE", resourceType = "VOTE", resourceIdIndex = 0)
    @CommonApiResponses
    @Operation(summary = "Update vote", description = "Edit a vote before it opens (creator only); options are replaced")
    public ResponseEntity<ApiResponse<VoteResponse>> updateVote(
            @Parameter(description = "Vote ID", required = true)
            @PathVariable String voteId,
            @Valid @RequestBody CreateVoteRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        VoteResponse response = voteService.updateVote(voteId, request, principal.userId());
        log.info("Vote {} updated by creator {}", voteId, principal.userId());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping("/my")
    @CommonApiResponses
    @Operation(summary = "Get user's votes", description = "Retrieve votes created by the authenticated user (paginated)")
    public ResponseEntity<ApiResponse<PageResponse<VoteResponse>>> getMyVotes(
            @PageableDefault(size = 20) Pageable pageable,
            @AuthenticationPrincipal UserPrincipal principal) {
        Page<VoteResponse> response = voteService.getVotesByCreator(principal.userId(), pageable);
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.from(response)));
    }

    @PostMapping("/{voteId}/vote")
    @Auditable(action = "VOTE", resourceType = "VOTE", resourceIdIndex = 0)
    @CommonApiResponses
    @Operation(summary = "Vote on a poll", description = "Cast vote(s) on the specified vote")
    public ResponseEntity<ApiResponse<VoteResponse>> vote(
            @Parameter(description = "Vote ID", required = true)
            @PathVariable String voteId,
            @Valid @RequestBody VoteRequest request,
            @AuthenticationPrincipal UserPrincipal principal,
            HttpServletRequest httpRequest) {
        String ipAddress = getClientIpAddress(httpRequest);
        VoteResponse response = voteService.vote(voteId, request, principal.userId(), ipAddress);
        log.info("User {} voted on vote {}", principal.userId(), voteId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @DeleteMapping("/{voteId}/vote/{optionId}")
    @CommonApiResponses
    @Operation(summary = "Remove vote", description = "Remove vote from a specific option")
    public ResponseEntity<ApiResponse<VoteResponse>> removeVote(
            @Parameter(description = "Vote ID", required = true)
            @PathVariable String voteId,
            @Parameter(description = "Option ID", required = true)
            @PathVariable String optionId,
            @AuthenticationPrincipal UserPrincipal principal) {
        VoteResponse response = voteService.removeVote(voteId, optionId, principal.userId());
        log.info("User {} removed vote from option {} in vote {}",
                principal.userId(), optionId, voteId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping("/{voteId}/results")
    @PublicApiResponse
    @Operation(summary = "Get vote results",
            description = "Public once the vote has ended (403 before). Voters are listed only for the creator of a non-anonymous vote")
    public ResponseEntity<ApiResponse<VoteResultResponse>> getVoteResults(
            @Parameter(description = "Vote ID", required = true)
            @PathVariable String voteId,
            @AuthenticationPrincipal UserPrincipal principal) {
        VoteResultResponse response = voteService.getVoteResults(voteId, viewerId(principal));
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping("/{voteId}/my-vote-status")
    @CommonApiResponses
    @Operation(summary = "Check user vote status", description = "Check if the authenticated user has voted and which options they selected")
    public ResponseEntity<ApiResponse<UserVoteStatusResponse>> getMyVoteStatus(
            @Parameter(description = "Vote ID", required = true)
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
    @Operation(summary = "Deactivate vote", description = "Deactivate a vote (only by creator)")
    public ResponseEntity<ApiResponse<SimpleResultResponse>> deactivateVote(
            @Parameter(description = "Vote ID", required = true)
            @PathVariable String voteId,
            @AuthenticationPrincipal UserPrincipal principal) {
        voteService.deactivateVote(voteId, principal.userId());
        log.info("Vote {} deactivated by creator {}", voteId, principal.userId());
        return ResponseEntity.ok(ApiResponse.ok(SimpleResultResponse.ok()));
    }

    /** Public endpoints run without a principal for guests. */
    private static String viewerId(UserPrincipal principal) {
        return principal != null ? principal.userId() : null;
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
