package com.vomatt.admin;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.vomatt.admin.dto.InvitationEmailLockResponse;
import com.vomatt.common.annotation.ApiError;
import com.vomatt.common.annotation.CommonApiResponses;
import com.vomatt.common.audit.Auditable;
import com.vomatt.common.email.InvitationEmailLimiter;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.response.ApiResponse;
import com.vomatt.common.response.SimpleResultResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/admin/invitation-email-locks")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - Invitation Email Locks", description = "Senders locked out of emailing an address (Admin)")
public class AdminInvitationEmailLockController {

    private final InvitationEmailLimiter invitationEmailLimiter;

    @GetMapping
    @CommonApiResponses
    @Operation(summary = "List invitation email locks", description = """
            **Auth**: admin (role ADMIN; 403 `common.forbidden` otherwise)
            **Precondition**: none
            **Behavior**: every sender currently locked out of emailing sign-up invitations (Invite List members, community \
            account invitations) to an address, the latest lock to end first. A sender who emails one address \
            `invitation_email_burst_limit` times (default 3) within one window of `invitation_email_burst_window_hours` \
            (default 12) is locked out of it for `invitation_email_lock_days` (default 7)
            **Side effects**: none
            **Errors**: none beyond the common ones""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Locks in force",
            content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Locks", value = """
                    { "success": true, "data": [ { "senderId": "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d47", "senderUsername": "alice",
                        "email": "dave@example.com", "lockedUntil": "2026-11-26T14:52:00+08:00" } ],
                      "message": null, "errorCode": null, "error": null }""")))
    public ResponseEntity<ApiResponse<List<InvitationEmailLockResponse>>> list() {
        return ResponseEntity.ok(ApiResponse.ok(invitationEmailLimiter.locks()));
    }

    @DeleteMapping("/{senderId}")
    @Auditable(action = "UNLOCK", resourceType = "INVITATION_EMAIL", resourceIdIndex = 0)
    @CommonApiResponses
    @Operation(summary = "Lift an invitation email lock", description = """
            **Auth**: admin (role ADMIN; 403 `common.forbidden` otherwise)
            **Precondition**: the sender is locked out of the address now
            **Behavior**: lifts the lock and resets the sender's count for the address, so their next email to it starts a new window
            **Side effects**: audit-logged as `UNLOCK` on `INVITATION_EMAIL` with the sender's id
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Lock lifted",
            content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Unlocked", value = """
                    { "success": true, "data": { "success": true }, "message": null, "errorCode": null, "error": null }""")))
    @ApiError(status = 404, key = MessageKey.INVITATION_EMAIL_LOCK_NOT_FOUND, when = "the sender is not locked out of the address (never was, or the lock already ended)")
    public ResponseEntity<ApiResponse<SimpleResultResponse>> unlock(
            @Parameter(description = "The locked sender's user id", required = true, example = "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d47")
            @PathVariable UUID senderId,
            @Parameter(description = "The locked address", required = true, example = "dave@example.com")
            @RequestParam String email) {
        invitationEmailLimiter.unlock(senderId, email);
        return ResponseEntity.ok(ApiResponse.ok(SimpleResultResponse.ok()));
    }
}
