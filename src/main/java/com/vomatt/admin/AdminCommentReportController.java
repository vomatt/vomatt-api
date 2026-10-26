package com.vomatt.admin;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.vomatt.admin.dto.AdminCommentReportResponse;
import com.vomatt.common.annotation.CommonApiResponses;
import com.vomatt.common.audit.Auditable;
import com.vomatt.common.response.ApiResponse;
import com.vomatt.common.response.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/admin/comment-reports")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - Comment Report", description = "Reading users' reports of Comments (Admin)")
public class AdminCommentReportController {

    private final AdminCommentReportService adminCommentReportService;

    @GetMapping
    @Auditable(action = "VIEW", resourceType = "COMMENT_REPORT")
    @CommonApiResponses
    @Operation(summary = "List Comment reports",
            description = """
                    **Auth**: admin (role ADMIN; 403 `common.forbidden` otherwise)
                    **Precondition**: caller holds role ADMIN
                    **Behavior**: offset page of reports from all users, newest first, each with the reported Comment as it is now (text and author kept when deleted), its author, the reporter, `reason` and `note`. A user reports a Comment at most once. Reports go away with the Comment's or the reporter's account. `page` is 1-based (default 1), `limit` 1-50 (default 20)
                    **Side effects**: written to the audit log (action `VIEW`, resource `COMMENT_REPORT`), since notes may hold personal data
                    **Errors**: none specific to this endpoint""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Page of reports",
            content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Reports", value = """
                    {
                      "success": true,
                      "data": {
                        "content": [
                          {
                            "id": "0199f4c2-1a2b-7c3d-8e4f-5a6b7c8d9e0f",
                            "commentId": "0199f3b1-2c4d-7e5f-8a9b-0c1d2e3f4a5b",
                            "voteId": "0199f2a3-5b7e-7d40-a1c8-9e3b2f6d4c05",
                            "parentId": null,
                            "commentText": "You are an idiot.",
                            "commentDeleted": false,
                            "authorId": "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d47",
                            "authorUsername": "troll",
                            "reporterId": "0199c1a2-9e4f-7a21-8c3d-1b2a3c4d5e6f",
                            "reporterUsername": "alice",
                            "reason": "HARASSMENT",
                            "note": "Insults me in every thread.",
                            "createdAt": "2026-10-26T10:00:00+08:00"
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
    public ResponseEntity<ApiResponse<PageResponse<AdminCommentReportResponse>>> listReports(
            @Parameter(description = "Page number, 1-based (default 1)") @RequestParam(required = false) Integer page,
            @Parameter(description = "Page size (1-50, default 20)") @RequestParam(required = false) Integer limit) {
        return ResponseEntity.ok(ApiResponse.ok(adminCommentReportService.listReports(page, limit)));
    }
}
