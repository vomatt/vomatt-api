package com.vomatt.admin;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.vomatt.admin.dto.AdminStatsResponse;
import com.vomatt.admin.dto.AuditLogResponse;
import com.vomatt.common.annotation.CommonApiResponses;
import com.vomatt.common.response.ApiResponse;
import com.vomatt.common.response.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - Overview", description = "Dashboard and audit log APIs (Admin)")
public class AdminController {

    private final AdminService adminService;

    @GetMapping("/stats")
    @CommonApiResponses
    @Operation(summary = "Get Dashboard Stats",
            description = """
                    **Auth**: admin (role ADMIN; 403 `common.forbidden` otherwise)
                    **Precondition**: caller holds role ADMIN
                    **Behavior**: returns live counts: users (total, created in the last 7 days, suspended), Polls (Open, Ended, Deleted; the first two leave Deleted Polls out) and Ballots cast or changed in the last 24 hours. Poll status is derived from the times at the moment of the request
                    **Side effects**: none
                    **Errors**: none beyond the common ones""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Dashboard counts",
            content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Stats", value = """
                    {
                      "success": true,
                      "data": {
                        "usersTotal": 1520,
                        "usersNewLast7Days": 84,
                        "usersSuspended": 3,
                        "pollsInProgress": 57,
                        "pollsEnded": 910,
                        "pollsDeleted": 12,
                        "ballotsLast24Hours": 433
                      },
                      "message": null,
                      "errorCode": null,
                      "error": null
                    }""")))
    public ResponseEntity<ApiResponse<AdminStatsResponse>> getStats() {
        return ResponseEntity.ok(ApiResponse.ok(adminService.getStats()));
    }

    @GetMapping("/audit-logs")
    @CommonApiResponses
    @Operation(summary = "List Audit Logs",
            description = """
                    **Auth**: admin (role ADMIN; 403 `common.forbidden` otherwise)
                    **Precondition**: caller holds role ADMIN
                    **Behavior**: read-only offset page of audit log entries, newest first. Filters (all optional, exact match, combined with AND): `userId` (actor), `action`, `resourceType`, `resourceId`, and the time range `from` (inclusive) to `to` (exclusive) as ISO-8601 date-times. `page` is 1-based (default 1), `limit` 1-50 (default 20). Entries are written asynchronously, so an action may show up a moment after it returns
                    **Side effects**: none
                    **Errors**:
                    - 400 `common.type_mismatch`: `userId` or `resourceId` is not a valid UUID, or `from` / `to` is not an ISO-8601 date-time""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Page of audit log entries",
            content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "AuditLogs", value = """
                    {
                      "success": true,
                      "data": {
                        "content": [
                          {
                            "id": "0199f4c0-1a2b-7c3d-8e4f-5a6b7c8d9e0f",
                            "userId": "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d00",
                            "username": "admin@example.com",
                            "action": "SUSPEND",
                            "resourceType": "USER",
                            "resourceId": "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d47",
                            "ipAddress": "203.0.113.7",
                            "userAgent": "Mozilla/5.0",
                            "requestUri": "/api/admin/users/0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d47/suspend",
                            "httpMethod": "PATCH",
                            "success": true,
                            "errorMessage": null,
                            "details": "Repeated spam in comments",
                            "createdAt": "2026-10-16T12:00:00+08:00"
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
    public ResponseEntity<ApiResponse<PageResponse<AuditLogResponse>>> listAuditLogs(
            @Parameter(description = "Actor's user ID (UUID)") @RequestParam(required = false) UUID userId,
            @Parameter(description = "Action, e.g. DELETE, SUSPEND, FORCE_CLOSE", example = "SUSPEND")
            @RequestParam(required = false) String action,
            @Parameter(description = "Resource type, e.g. VOTE, COMMENT, USER", example = "USER")
            @RequestParam(required = false) String resourceType,
            @Parameter(description = "Affected resource ID (UUID; every audited resource has a UUID id)") @RequestParam(required = false) UUID resourceId,
            @Parameter(description = "Inclusive start, ISO-8601 (URL-encode a `+` offset as `%2B`)", example = "2026-10-01T00:00:00Z")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @Parameter(description = "Exclusive end, ISO-8601 (URL-encode a `+` offset as `%2B`)", example = "2026-10-17T00:00:00Z")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @Parameter(description = "Page number, 1-based (default 1)") @RequestParam(required = false) Integer page,
            @Parameter(description = "Page size (1-50, default 20)") @RequestParam(required = false) Integer limit) {
        return ResponseEntity.ok(ApiResponse.ok(
                adminService.listAuditLogs(userId, action, resourceType, resourceId, from, to, page, limit)));
    }
}
