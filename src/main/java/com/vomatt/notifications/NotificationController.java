package com.vomatt.notifications;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.vomatt.common.annotation.CommonApiResponses;
import com.vomatt.common.response.ApiResponse;
import com.vomatt.common.response.SimpleResultResponse;
import com.vomatt.common.security.UserPrincipal;
import com.vomatt.notifications.dto.UnreadCountResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
@Tag(name = "Notification", description = "Poll ended notifications (list via GET /api/votes/my?status=ended)")
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping("/unread-count")
    @CommonApiResponses
    @Operation(summary = "Unread notification count",
            description = "Ended Polls the caller owns or held a Ballot in, not yet marked read")
    public ResponseEntity<ApiResponse<UnreadCountResponse>> unreadCount(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(notificationService.countUnread(principal.userId())));
    }

    @PostMapping("/{voteId}/read")
    @CommonApiResponses
    @Operation(summary = "Mark a notification read", description = "Idempotent; 404 when the caller has no notification for this Poll")
    public ResponseEntity<ApiResponse<SimpleResultResponse>> markRead(
            @Parameter(description = "Vote ID", required = true) @PathVariable String voteId,
            @AuthenticationPrincipal UserPrincipal principal) {
        notificationService.markRead(principal.userId(), voteId);
        return ResponseEntity.ok(ApiResponse.ok(SimpleResultResponse.ok()));
    }
}
