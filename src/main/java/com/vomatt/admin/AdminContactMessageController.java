package com.vomatt.admin;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.vomatt.admin.dto.AdminContactMessageResponse;
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
@RequestMapping("/api/admin/contact-messages")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - Contact messages", description = "Reading contact form messages (Admin)")
public class AdminContactMessageController {

    private final AdminContactMessageService adminContactMessageService;

    @GetMapping
    @Auditable(action = "VIEW", resourceType = "CONTACT_MESSAGE")
    @CommonApiResponses
    @Operation(summary = "List contact messages",
            description = """
                    **Auth**: admin (role ADMIN; 403 `common.forbidden` otherwise)
                    **Precondition**: caller holds role ADMIN
                    **Behavior**: offset page of messages sent from the about site's contact form, newest first. `page` is 1-based (default 1), `limit` 1-50 (default 20)
                    **Side effects**: written to the audit log (action `VIEW`, resource `CONTACT_MESSAGE`), since messages hold names and email addresses
                    **Errors**: none beyond the common ones""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Page of contact messages",
            content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Contact messages", value = """
                    {
                      "success": true,
                      "data": {
                        "content": [
                          {
                            "id": "0199c3a2-7b5e-7c1d-9a4f-3e2b1d5c6f70",
                            "name": "Alice Chen",
                            "email": "alice@example.com",
                            "message": "Can my school use Vomatt for student council elections?",
                            "locale": "en",
                            "createdAt": "2026-11-07T10:00:00+08:00"
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
    public ResponseEntity<ApiResponse<PageResponse<AdminContactMessageResponse>>> listMessages(
            @Parameter(description = "Page number, 1-based (default 1)") @RequestParam(required = false) Integer page,
            @Parameter(description = "Page size (1-50, default 20)") @RequestParam(required = false) Integer limit) {
        return ResponseEntity.ok(ApiResponse.ok(adminContactMessageService.listMessages(page, limit)));
    }
}
