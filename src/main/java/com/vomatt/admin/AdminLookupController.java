package com.vomatt.admin;

import com.vomatt.common.annotation.ApiError;
import com.vomatt.common.audit.Auditable;
import java.util.List;

import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.response.ApiResponse;
import com.vomatt.common.util.TextUtil;
import com.vomatt.common.response.SimpleResultResponse;
import com.vomatt.common.annotation.CommonApiResponses;
import com.vomatt.lookups.dto.CreateLookupRequest;
import com.vomatt.lookups.dto.LookupDto;
import com.vomatt.lookups.dto.UpdateLookupRequest;
import com.vomatt.lookups.LookupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/lookups")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - Lookup", description = "Dictionary management APIs (Admin)")
public class AdminLookupController {

    private final LookupService lookupService;

    @GetMapping
    @CommonApiResponses
    @Operation(summary = "List Dictionary Items",
            description = """
                    **Auth**: admin (role ADMIN; 403 `common.forbidden` otherwise)
                    **Precondition**: caller holds role ADMIN
                    **Behavior**: lists the items of `type` (every type when omitted), ordered by type then `seq`. Unlike the public listing, `includeInactive` (default true) also returns deactivated items so they can be re-activated. Not cached, not paged
                    **Side effects**: none
                    """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Dictionary items",
            content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Lookups", value = """
                    {
                      "success": true,
                      "data": [
                        {
                          "id": "0199c3a2-7b5e-7c1d-9a4f-3e2b1d5c6f71",
                          "lookupType": "vote_duration",
                          "lookupKey": "24h",
                          "lookupValue": { "label": "24 hours", "hours": 24 },
                          "seq": 1,
                          "parentType": null,
                          "parentKey": null,
                          "isActive": false,
                          "description": "Poll duration presets",
                          "frontendUsing": true
                        }
                      ],
                      "message": null,
                      "errorCode": null,
                      "error": null
                    }""")))
    @ApiError(status = 400, key = MessageKey.COMMON_TYPE_MISMATCH, when = "`includeInactive` is not a boolean", args = { "limit" })
    public ResponseEntity<ApiResponse<List<LookupDto>>> listLookups(
            @Parameter(description = "Dictionary type; omit for every type", example = "vote_duration")
            @RequestParam(required = false) String type,
            @Parameter(description = "Include deactivated items (default true)", example = "true")
            @RequestParam(defaultValue = "true") boolean includeInactive) {
        return ResponseEntity.ok(ApiResponse.ok(lookupService.listForAdmin(TextUtil.blankToNull(type), includeInactive)));
    }

    @PostMapping
    @Auditable(action = "CREATE", resourceType = "LOOKUP")
    @CommonApiResponses
    @Operation(summary = "Create Dictionary Item",
            description = """
                    **Auth**: admin (role ADMIN; 403 `common.forbidden` otherwise)
                    **Precondition**: caller holds role ADMIN
                    **Behavior**: creates an active item; `type` and `key` are trimmed, `seq` defaults to 0 and `frontendUsing` to false. Returns **201**
                    **Side effects**: evicts the cached listing of this type, and the frontend dictionary when `frontendUsing` is true. Written to the audit log
                    """)
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
            examples = @ExampleObject(name = "CreateLookup", value = """
                            {
                              "lookupType": "vote_duration",
                              "lookupKey": "24h",
                              "lookupValue": { "label": "24 hours", "hours": 24 },
                              "seq": 1,
                              "description": "Poll duration presets",
                              "frontendUsing": true
                            }""")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Dictionary item created",
            content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "CreatedLookup", value = """
                    {
                      "success": true,
                      "data": {
                        "id": "0199c3a2-7b5e-7c1d-9a4f-3e2b1d5c6f71",
                        "lookupType": "vote_duration",
                        "lookupKey": "24h",
                        "lookupValue": { "label": "24 hours", "hours": 24 },
                        "seq": 1,
                        "parentType": null,
                        "parentKey": null,
                        "isActive": true,
                        "description": "Poll duration presets",
                        "frontendUsing": true
                      },
                      "message": null,
                      "errorCode": null,
                      "error": null
                    }""")))
    @ApiError(status = 409, key = MessageKey.LOOKUP_EXISTS, when = "an item with this type and key already exists", args = { "vote_duration", "24h" })
    public ResponseEntity<ApiResponse<LookupDto>> create(
            @Valid @RequestBody CreateLookupRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(lookupService.create(request)));
    }

    @PutMapping("/{id}")
    @Auditable(action = "UPDATE", resourceType = "LOOKUP", resourceIdIndex = 0)
    @CommonApiResponses
    @Operation(summary = "Update Dictionary Item",
            description = """
                    **Auth**: admin (role ADMIN; 403 `common.forbidden` otherwise)
                    **Precondition**: caller holds role ADMIN; the item exists
                    **Behavior**: partial update despite the PUT verb: only non-null fields are applied, and a field cannot be cleared to null. Changing `lookupType` or `lookupKey` moves the item
                    **Side effects**: evicts the cached listings of the old and the new type, and the frontend dictionary when the item is or was flagged `frontendUsing`. Written to the audit log
                    """)
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
            examples = @ExampleObject(name = "UpdateLookup", value = """
                            {
                              "lookupValue": { "label": "One day", "hours": 24 },
                              "seq": 2
                            }""")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Dictionary item updated",
            content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "UpdatedLookup", value = """
                    {
                      "success": true,
                      "data": {
                        "id": "0199c3a2-7b5e-7c1d-9a4f-3e2b1d5c6f71",
                        "lookupType": "vote_duration",
                        "lookupKey": "24h",
                        "lookupValue": { "label": "24 hours", "hours": 24 },
                        "seq": 1,
                        "parentType": null,
                        "parentKey": null,
                        "isActive": true,
                        "description": "Poll duration presets",
                        "frontendUsing": true
                      },
                      "message": null,
                      "errorCode": null,
                      "error": null
                    }""")))
    @ApiError(status = 400, key = MessageKey.COMMON_BAD_REQUEST, when = "`id` is not a valid UUID")
    @ApiError(status = 400, key = MessageKey.COMMON_VALIDATION_FAILED, when = "`lookupType` or `lookupKey` is blank, or a field is too long")
    @ApiError(status = 404, key = MessageKey.LOOKUP_NOT_FOUND, when = "item does not exist")
    @ApiError(status = 409, key = MessageKey.LOOKUP_EXISTS, when = "the resulting type and key belong to another item", args = { "vote_duration", "24h" })
    public ResponseEntity<ApiResponse<LookupDto>> update(
            @Parameter(description = "Dictionary item ID (UUIDv7)", required = true) @PathVariable String id,
            @Valid @RequestBody UpdateLookupRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(lookupService.update(id, request)));
    }

    @PatchMapping("/{id}/activate")
    @Auditable(action = "ACTIVATE", resourceType = "LOOKUP", resourceIdIndex = 0)
    @CommonApiResponses
    @Operation(summary = "Activate Dictionary Item",
            description = """
                    **Auth**: admin (role ADMIN; 403 `common.forbidden` otherwise)
                    **Precondition**: caller holds role ADMIN; the item exists
                    **Behavior**: sets `isActive` to true; idempotent
                    **Side effects**: evicts the cached type listing, and the frontend dictionary when the item is flagged `frontendUsing`. Written to the audit log
                    """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Dictionary item activated",
            content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Activated", value = """
                    {
                      "success": true,
                      "data": {
                        "success": true,
                        "id": null,
                        "status": null,
                        "message": null
                      },
                      "message": null,
                      "errorCode": null,
                      "error": null
                    }""")))
    @ApiError(status = 400, key = MessageKey.COMMON_BAD_REQUEST, when = "`id` is not a valid UUID")
    @ApiError(status = 404, key = MessageKey.LOOKUP_NOT_FOUND, when = "item does not exist")
    public ResponseEntity<ApiResponse<SimpleResultResponse>> activate(
            @Parameter(description = "Dictionary item ID (UUIDv7)", required = true) @PathVariable String id) {
        lookupService.activate(id);
        return ResponseEntity.ok(ApiResponse.ok(SimpleResultResponse.ok()));
    }

    @PatchMapping("/{id}/deactivate")
    @Auditable(action = "DEACTIVATE", resourceType = "LOOKUP", resourceIdIndex = 0)
    @CommonApiResponses
    @Operation(summary = "Deactivate Dictionary Item (Soft Delete)",
            description = """
                    **Auth**: admin (role ADMIN; 403 `common.forbidden` otherwise)
                    **Precondition**: caller holds role ADMIN; the item exists
                    **Behavior**: sets `isActive` to false; the item stays retrievable through the single-item endpoint but leaves type listings and the frontend dictionary. Idempotent
                    **Side effects**: evicts the cached type listing, and the frontend dictionary when the item is flagged `frontendUsing`. Written to the audit log
                    """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Dictionary item deactivated",
            content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Deactivated", value = """
                    {
                      "success": true,
                      "data": {
                        "success": true,
                        "id": null,
                        "status": null,
                        "message": null
                      },
                      "message": null,
                      "errorCode": null,
                      "error": null
                    }""")))
    @ApiError(status = 400, key = MessageKey.COMMON_BAD_REQUEST, when = "`id` is not a valid UUID")
    @ApiError(status = 404, key = MessageKey.LOOKUP_NOT_FOUND, when = "item does not exist")
    public ResponseEntity<ApiResponse<SimpleResultResponse>> deactivate(
            @Parameter(description = "Dictionary item ID (UUIDv7)", required = true) @PathVariable String id) {
        lookupService.deactivate(id);
        return ResponseEntity.ok(ApiResponse.ok(SimpleResultResponse.ok()));
    }

    @DeleteMapping("/{id}")
    @Auditable(action = "DELETE", resourceType = "LOOKUP", resourceIdIndex = 0)
    @CommonApiResponses
    @Operation(summary = "Delete Dictionary Item (Hard Delete)",
            description = """
                    **Auth**: admin (role ADMIN; 403 `common.forbidden` otherwise)
                    **Precondition**: caller holds role ADMIN; the item exists
                    **Behavior**: permanently removes the item; prefer deactivation when it may be needed again
                    **Side effects**: evicts the cached type listing, and the frontend dictionary when the item is flagged `frontendUsing`. Written to the audit log
                    """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Dictionary item deleted",
            content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Deleted", value = """
                    {
                      "success": true,
                      "data": {
                        "success": true,
                        "id": null,
                        "status": null,
                        "message": null
                      },
                      "message": null,
                      "errorCode": null,
                      "error": null
                    }""")))
    @ApiError(status = 400, key = MessageKey.COMMON_BAD_REQUEST, when = "`id` is not a valid UUID")
    @ApiError(status = 404, key = MessageKey.LOOKUP_NOT_FOUND, when = "item does not exist")
    public ResponseEntity<ApiResponse<SimpleResultResponse>> delete(
            @Parameter(description = "Dictionary item ID (UUIDv7)", required = true) @PathVariable String id) {
        lookupService.delete(id);
        return ResponseEntity.ok(ApiResponse.ok(SimpleResultResponse.ok()));
    }
}
