package com.vomatt.notifications;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.vomatt.common.annotation.CommonApiResponses;
import com.vomatt.common.config.OpenAPIConfig;
import com.vomatt.common.response.ApiResponse;
import com.vomatt.common.security.UserPrincipal;
import com.vomatt.notifications.dto.NotificationPreferencesResponse;
import com.vomatt.notifications.dto.UpdateNotificationPreferencesRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/users/me/notification-preferences")
@RequiredArgsConstructor
@Tag(name = "Notification")
public class NotificationPreferenceController {

    private final NotificationService notificationService;

    @GetMapping
    @CommonApiResponses
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success",
            content = @Content(examples = @ExampleObject(value = """
                    {"success":true,"data":{"resultsIn":true,"invitations":true,"comments":true,"closingSoon":false,"likes":true},"message":null,"errorCode":null,"error":null}""")))
    @Operation(summary = "Get notification preferences", description = """
            **Auth**: required
            **Precondition**: none
            **Behavior**: which notification kinds the feed and the unread count include. Defaults: `resultsIn`, `invitations`, \
            `comments` and `likes` on, `closingSoon` off. FOLLOWED has no switch. There is no push or email channel; these switches only \
            filter the in-app feed.
            **Side effects**: none
            **Errors**: none specific to this endpoint""")
    public ResponseEntity<ApiResponse<NotificationPreferencesResponse>> get(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(notificationService.getPreferences(principal.userId())));
    }

    @PatchMapping
    @CommonApiResponses
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success",
            content = @Content(examples = @ExampleObject(value = """
                    {"success":true,"data":{"resultsIn":true,"invitations":true,"comments":false,"closingSoon":true,"likes":true},"message":null,"errorCode":null,"error":null}""")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Account no longer exists (errorCode `user.not_found`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(value = """
                            {"success":false,"data":null,"message":"User not found","errorCode":"user.not_found","error":"User not found"}""")))
    @Operation(summary = "Update notification preferences", description = """
            **Auth**: required
            **Precondition**: none
            **Behavior**: sets the switches sent; an omitted or `null` field keeps its value. Returns every switch after the change. \
            A kind switched off leaves the feed and the unread count at once; switched back on, its items return with their \
            read state (and `POST /api/notifications/read-all` covers them too).
            **Side effects**: persists the switches; they also appear under `preferences` in `GET /api/users/me/export`
            **Errors**:
            - 404 `user.not_found`: the account no longer exists""")
    public ResponseEntity<ApiResponse<NotificationPreferencesResponse>> update(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(examples = @ExampleObject(value = """
                    {"comments":false,"closingSoon":true}"""))) @RequestBody UpdateNotificationPreferencesRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(notificationService.updatePreferences(principal.userId(), request)));
    }
}
