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
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import com.vomatt.common.config.OpenAPIConfig;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
@Tag(name = "Notification", description = "Ended Notifications, derived when read (no push). The list itself is `GET /api/votes/my?status=ended`, whose items carry `unread`")
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping("/unread-count")
    @CommonApiResponses
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success",
            content = @Content(examples = @ExampleObject(value = """
                    {"success":true,"data":{"count":3},"message":null,"errorCode":null,"error":null}""")))
    @Operation(summary = "Unread notification count", description = """
            **Auth**: required
            **Precondition**: none
            **Behavior**: counts the caller's unread Ended Notifications. Notifications are not stored: a Poll counts \
            once it has Ended (end time passed or Closed early), the caller owns it or holds a Ballot in it, it was not \
            Cancelled while Scheduled, and the caller has not marked it read. A Participant who made a Retraction no longer counts. \
            Poll this endpoint (for example on app focus and about once a minute) to drive a badge; there is no push.
            **Side effects**: none
            **Errors**: none specific to this endpoint""")
    public ResponseEntity<ApiResponse<UnreadCountResponse>> unreadCount(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(notificationService.countUnread(principal.userId())));
    }

    @PostMapping("/{voteId}/read")
    @CommonApiResponses
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success",
            content = @Content(examples = @ExampleObject(value = """
                    {"success":true,"data":{"success":true,"id":null,"status":null,"message":null},"message":null,"errorCode":null,"error":null}""")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Caller has no Ended Notification for this Poll (errorCode `notification.not_found`)",
            content = @Content(schema = @Schema(ref = "#/components/schemas/" + OpenAPIConfig.ERROR_SCHEMA),
                    examples = @ExampleObject(value = """
                            {"success":false,"data":null,"message":"Notification not found","errorCode":"notification.not_found","error":"Notification not found"}""")))
    @Operation(summary = "Mark an Ended Notification read", description = """
            **Auth**: required
            **Precondition**: the Poll has Ended, was not Cancelled while Scheduled, and the caller owns it or holds a Ballot in it
            **Behavior**: marks the caller's Ended Notification for this Poll as read. Idempotent: marking an already-read notification succeeds and changes nothing.
            **Side effects**: the unread count drops by 1 (first call only); `unread` becomes false on the Poll in `GET /api/votes/my?status=ended`
            **Errors**:
            - 404 `notification.not_found`: the Poll has not Ended, was Cancelled while Scheduled, does not exist, or the caller neither owns it nor holds a Ballot in it
            - 400 `common.bad_request`: `voteId` is not a valid id""")
    public ResponseEntity<ApiResponse<SimpleResultResponse>> markRead(
            @Parameter(description = "Id of the Poll whose Ended Notification is marked read", required = true, example = "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d47") @PathVariable String voteId,
            @AuthenticationPrincipal UserPrincipal principal) {
        notificationService.markRead(principal.userId(), voteId);
        return ResponseEntity.ok(ApiResponse.ok(SimpleResultResponse.ok()));
    }
}
