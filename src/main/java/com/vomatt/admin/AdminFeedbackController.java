package com.vomatt.admin;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.vomatt.admin.dto.AdminFeedbackResponse;
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
@RequestMapping("/api/admin/feedback")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - Feedback", description = "Reading user feedback (Admin)")
public class AdminFeedbackController {

    private final AdminFeedbackService adminFeedbackService;

    @GetMapping
    @Auditable(action = "VIEW", resourceType = "FEEDBACK", resourceIdIndex = 0)
    @CommonApiResponses
    @Operation(summary = "List feedback",
            description = """
                    **Auth**: admin (role ADMIN; 403 `common.forbidden` otherwise)
                    **Precondition**: caller holds role ADMIN
                    **Behavior**: offset page of feedback from all users, newest first; `userId` narrows it to one sender. `page` is 1-based (default 1), `limit` 1-50 (default 20)
                    **Side effects**: written to the audit log (action `VIEW`, resource `FEEDBACK`, resource id = `userId` when given), since feedback text may hold personal data
                    **Errors**:
                    - 400 `common.type_mismatch`: `userId` is not a valid UUID""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Page of feedback",
            content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Feedback", value = """
                    {
                      "success": true,
                      "data": {
                        "content": [
                          {
                            "id": "0199c3a2-7b5e-7c1d-9a4f-3e2b1d5c6f70",
                            "userId": "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d47",
                            "username": "alice",
                            "content": "Please add dark mode.",
                            "createdAt": "2026-10-23T10:00:00+08:00"
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
    public ResponseEntity<ApiResponse<PageResponse<AdminFeedbackResponse>>> listFeedback(
            @Parameter(description = "Only feedback from this user (UUID)") @RequestParam(required = false) UUID userId,
            @Parameter(description = "Page number, 1-based (default 1)") @RequestParam(required = false) Integer page,
            @Parameter(description = "Page size (1-50, default 20)") @RequestParam(required = false) Integer limit) {
        return ResponseEntity.ok(ApiResponse.ok(adminFeedbackService.listFeedback(userId, page, limit)));
    }
}
