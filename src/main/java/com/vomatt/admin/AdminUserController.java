package com.vomatt.admin;

import java.util.UUID;

import com.vomatt.admin.dto.AdminUserDetail;
import com.vomatt.admin.dto.AdminUserResponse;
import com.vomatt.admin.dto.SuspendUserRequest;
import com.vomatt.admin.dto.UpdateRolesRequest;
import com.vomatt.common.annotation.CommonApiResponses;
import com.vomatt.common.audit.Auditable;
import com.vomatt.common.config.OpenAPIConfig;
import com.vomatt.common.response.ApiResponse;
import com.vomatt.common.response.PageResponse;
import com.vomatt.common.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - User", description = "Account moderation APIs (Admin)")
public class AdminUserController {

    // Same account before and after suspension; only "active" differs
    private static final String USER_DETAIL_HEAD = """
            {
              "success": true,
              "data": {
                "id": "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d47",
                "username": "alice",
                "displayName": "Alice",
                "email": "alice@example.com",
                "phoneNumber": null,
                "authMethod": "email_otp",
                "roles": ["user"],
                "active": """;

    private static final String USER_DETAIL_TAIL = """
            ,
                "lastLoginAt": "2026-10-16T21:04:11+08:00",
                "points": 120,
                "membershipLevel": "BASIC",
                "createdAt": "2026-09-01T10:00:00+08:00",
                "pollCount": 8,
                "ballotCount": 35,
                "commentCount": 14
              },
              "message": null,
              "errorCode": null,
              "error": null
            }""";

    private static final String USER_DETAIL_EXAMPLE = USER_DETAIL_HEAD + "true" + USER_DETAIL_TAIL;

    private static final String SUSPENDED_USER_EXAMPLE = USER_DETAIL_HEAD + "false" + USER_DETAIL_TAIL;

    private static final String USER_NOT_FOUND_EXAMPLE = """
            {
              "success": false,
              "data": null,
              "message": "User not found",
              "errorCode": "user.not_found",
              "error": "User not found"
            }""";

    private final AdminUserService adminUserService;

    @GetMapping
    @CommonApiResponses
    @Operation(summary = "List Users",
            description = """
                    **Auth**: admin (role ADMIN; 403 `common.forbidden` otherwise)
                    **Precondition**: caller holds role ADMIN
                    **Behavior**: offset page of every account, suspended included, newest first. `q` matches (case-insensitive contains) username, email, phone number or display name; `status` is `active` or `suspended`; `role` is `user`, `moderator` or `admin`. All filters are optional and combine with AND. `page` is 1-based (default 1), `limit` 1-50 (default 20)
                    **Side effects**: none
                    **Errors**:
                    - 400 `common.invalid_status`: `status` is neither `active` nor `suspended`
                    - 400 `common.validation_failed`: `role` is not `user`, `moderator` or `admin`""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Page of users",
            content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Users", value = """
                    {
                      "success": true,
                      "data": {
                        "content": [
                          {
                            "id": "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d47",
                            "username": "alice",
                            "displayName": "Alice",
                            "email": "alice@example.com",
                            "phoneNumber": null,
                            "authMethod": "email_otp",
                            "roles": ["user"],
                            "active": true,
                            "lastLoginAt": "2026-10-16T21:04:11+08:00",
                            "createdAt": "2026-09-01T10:00:00+08:00"
                          }
                        ],
                        "total": 1,
                        "page": 1,
                        "limit": 20
                      },
                      "message": null,
                      "errorCode": null,
                      "error": null
                    }""")))
    public ResponseEntity<ApiResponse<PageResponse<AdminUserResponse>>> listUsers(
            @Parameter(description = "Text matched against username, email, phone number and display name", example = "alice")
            @RequestParam(required = false) String q,
            @Parameter(description = "`active` or `suspended`; omit for both", example = "suspended")
            @RequestParam(required = false) String status,
            @Parameter(description = "Only accounts holding this role: `user`, `moderator` or `admin`", example = "admin")
            @RequestParam(required = false) String role,
            @Parameter(description = "Page number, 1-based (default 1)") @RequestParam(required = false) Integer page,
            @Parameter(description = "Page size (1-50, default 20)") @RequestParam(required = false) Integer limit) {
        return ResponseEntity.ok(ApiResponse.ok(adminUserService.listUsers(q, status, role, page, limit)));
    }

    @GetMapping("/{userId}")
    @CommonApiResponses
    @Operation(summary = "Get User",
            description = """
                    **Auth**: admin (role ADMIN; 403 `common.forbidden` otherwise)
                    **Precondition**: caller holds role ADMIN
                    **Behavior**: returns the account with sign-in details, points and counts of Polls created (any Audience, Deleted included), Ballots held and Comments written (deleted included)
                    **Side effects**: none
                    **Errors**:
                    - 400 `common.type_mismatch`: `userId` is not a valid UUID
                    - 404 `user.not_found`: no account with this ID""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "The account",
            content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "User", value = USER_DETAIL_EXAMPLE)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "User not found (`user.not_found`)",
            content = @Content(mediaType = "application/json", schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "UserNotFound", value = USER_NOT_FOUND_EXAMPLE)))
    public ResponseEntity<ApiResponse<AdminUserDetail>> getUser(
            @Parameter(description = "User ID (UUIDv7)", required = true) @PathVariable UUID userId) {
        return ResponseEntity.ok(ApiResponse.ok(adminUserService.getUser(userId)));
    }

    @PatchMapping("/{userId}/suspend")
    @Auditable(action = "SUSPEND", resourceType = "USER", resourceIdIndex = 0, detailsIndex = 1)
    @CommonApiResponses
    @Operation(summary = "Suspend User",
            description = """
                    **Auth**: admin (role ADMIN; 403 `common.forbidden` otherwise)
                    **Precondition**: the account exists, is not the caller, and is not the last active admin
                    **Behavior**: sets `active` to false and returns the account. Idempotent: suspending a suspended account succeeds. The body is optional; its `reason` is audit-only (kept in the audit log entry's `details`, not on the account), and the audit log is written asynchronously on a best-effort basis
                    **Side effects**: once the suspension commits, every refresh token of the user is revoked, so they cannot refresh or sign in; their unexpired access token still reads but every write gets 403 `auth.account_banned`, and a suspended admin loses back-office access on the next request (403 `auth.account_banned`). Written to the audit log with the reason in `details`
                    **Errors**:
                    - 400 `common.type_mismatch`: `userId` is not a valid UUID
                    - 400 `common.validation_failed`: `reason` is longer than 500 characters
                    - 403 `admin.self_action_forbidden`: the caller tried to suspend themself
                    - 404 `user.not_found`: no account with this ID
                    - 409 `admin.last_admin`: the account is the only active admin
                    - 409 `common.conflict`: another admin change holds the admin rows; retry""")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
            examples = @ExampleObject(name = "Suspend", value = """
                    {
                      "reason": "Repeated spam in comments"
                    }""")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "User suspended",
            content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Suspended", value = SUSPENDED_USER_EXAMPLE)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller targeted themself (`admin.self_action_forbidden`) or is not an admin (`common.forbidden`)",
            content = @Content(mediaType = "application/json", schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "SelfAction", value = """
                            {
                              "success": false,
                              "data": null,
                              "message": "You cannot suspend yourself or remove your own admin role",
                              "errorCode": "admin.self_action_forbidden",
                              "error": "You cannot suspend yourself or remove your own admin role"
                            }""")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "User not found (`user.not_found`)",
            content = @Content(mediaType = "application/json", schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "UserNotFound", value = USER_NOT_FOUND_EXAMPLE)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Only active admin (`admin.last_admin`)",
            content = @Content(mediaType = "application/json", schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "LastAdmin", value = """
                            {
                              "success": false,
                              "data": null,
                              "message": "Cannot remove the last active admin",
                              "errorCode": "admin.last_admin",
                              "error": "Cannot remove the last active admin"
                            }""")))
    public ResponseEntity<ApiResponse<AdminUserDetail>> suspendUser(
            @Parameter(description = "User ID (UUIDv7)", required = true) @PathVariable UUID userId,
            @Valid @RequestBody(required = false) SuspendUserRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(adminUserService.suspend(userId, principal.userId())));
    }

    @PatchMapping("/{userId}/unsuspend")
    @Auditable(action = "UNSUSPEND", resourceType = "USER", resourceIdIndex = 0)
    @CommonApiResponses
    @Operation(summary = "Unsuspend User",
            description = """
                    **Auth**: admin (role ADMIN; 403 `common.forbidden` otherwise)
                    **Precondition**: the account exists
                    **Behavior**: sets `active` to true and returns the account. Idempotent
                    **Side effects**: the user can sign in again (revoked refresh tokens stay revoked). Written to the audit log
                    **Errors**:
                    - 400 `common.type_mismatch`: `userId` is not a valid UUID
                    - 404 `user.not_found`: no account with this ID""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "User unsuspended",
            content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Unsuspended", value = USER_DETAIL_EXAMPLE)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "User not found (`user.not_found`)",
            content = @Content(mediaType = "application/json", schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "UserNotFound", value = USER_NOT_FOUND_EXAMPLE)))
    public ResponseEntity<ApiResponse<AdminUserDetail>> unsuspendUser(
            @Parameter(description = "User ID (UUIDv7)", required = true) @PathVariable UUID userId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(adminUserService.unsuspend(userId, principal.userId())));
    }

    @PutMapping("/{userId}/roles")
    @Auditable(action = "UPDATE_ROLES", resourceType = "USER", resourceIdIndex = 0, detailsIndex = 1)
    @CommonApiResponses
    @Operation(summary = "Set User Roles",
            description = """
                    **Auth**: admin (role ADMIN; 403 `common.forbidden` otherwise)
                    **Precondition**: the account exists; when the change drops the admin role, the account is not the caller and not the last active admin
                    **Behavior**: replaces the account's roles with `roles` (each `user`, `moderator` or `admin`; duplicates ignored) and returns the account
                    **Side effects**: when the role set actually changes, every refresh token of the user is revoked once the change commits, so the new roles apply from their next sign-in; an access token already issued keeps its old roles until it expires, except that a removed admin role stops working on `/api/admin/**` on the next request. An unchanged role set changes nothing. Written to the audit log with the new roles in `details`
                    **Errors**:
                    - 400 `common.type_mismatch`: `userId` is not a valid UUID
                    - 400 `common.validation_failed`: `roles` is empty or holds an unknown role
                    - 403 `admin.self_action_forbidden`: the caller tried to drop their own admin role
                    - 404 `user.not_found`: no account with this ID
                    - 409 `admin.last_admin`: the change would demote the only active admin
                    - 409 `common.conflict`: another admin change holds the admin rows; retry""")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
            examples = @ExampleObject(name = "Roles", value = """
                    {
                      "roles": ["user", "moderator"]
                    }""")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Roles updated",
            content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "RolesUpdated", value = USER_DETAIL_EXAMPLE)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller tried to drop their own admin role (`admin.self_action_forbidden`) or is not an admin (`common.forbidden`)",
            content = @Content(mediaType = "application/json", schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "SelfAction", value = """
                            {
                              "success": false,
                              "data": null,
                              "message": "You cannot suspend yourself or remove your own admin role",
                              "errorCode": "admin.self_action_forbidden",
                              "error": "You cannot suspend yourself or remove your own admin role"
                            }""")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "User not found (`user.not_found`)",
            content = @Content(mediaType = "application/json", schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "UserNotFound", value = USER_NOT_FOUND_EXAMPLE)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Would demote the only active admin (`admin.last_admin`)",
            content = @Content(mediaType = "application/json", schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "LastAdmin", value = """
                            {
                              "success": false,
                              "data": null,
                              "message": "Cannot remove the last active admin",
                              "errorCode": "admin.last_admin",
                              "error": "Cannot remove the last active admin"
                            }""")))
    public ResponseEntity<ApiResponse<AdminUserDetail>> updateRoles(
            @Parameter(description = "User ID (UUIDv7)", required = true) @PathVariable UUID userId,
            @Valid @RequestBody UpdateRolesRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(adminUserService.updateRoles(userId, request.roles(), principal.userId())));
    }
}
