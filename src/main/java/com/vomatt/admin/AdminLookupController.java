package com.vomatt.admin;

import com.vomatt.common.response.ApiResponse;
import com.vomatt.common.response.SimpleResultResponse;
import com.vomatt.common.annotation.CommonApiResponses;
import com.vomatt.lookups.dto.CreateLookupRequest;
import com.vomatt.lookups.dto.LookupDto;
import com.vomatt.lookups.dto.UpdateLookupRequest;
import com.vomatt.lookups.LookupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/lookups")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - Lookup", description = "Dictionary management APIs (Admin)")
public class AdminLookupController {

    private final LookupService lookupService;

    @PostMapping
    @CommonApiResponses
    @Operation(summary = "Create Dictionary Item")
    public ResponseEntity<ApiResponse<LookupDto>> create(
            @Valid @RequestBody CreateLookupRequest request) {
        LookupDto result = lookupService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(result));
    }

    @PutMapping("/{id}")
    @CommonApiResponses
    @Operation(summary = "Update Dictionary Item")
    public ResponseEntity<ApiResponse<LookupDto>> update(
            @Parameter(description = "Dictionary ID", required = true) @PathVariable String id,
            @Valid @RequestBody UpdateLookupRequest request) {
        LookupDto result = lookupService.update(id, request);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @PatchMapping("/{id}/activate")
    @CommonApiResponses
    @Operation(summary = "Activate Dictionary Item")
    public ResponseEntity<ApiResponse<SimpleResultResponse>> activate(
            @Parameter(description = "Dictionary ID", required = true) @PathVariable String id) {
        lookupService.activate(id);
        return ResponseEntity.ok(ApiResponse.ok(SimpleResultResponse.ok()));
    }

    @PatchMapping("/{id}/deactivate")
    @CommonApiResponses
    @Operation(summary = "Deactivate Dictionary Item (Soft Delete)")
    public ResponseEntity<ApiResponse<SimpleResultResponse>> deactivate(
            @Parameter(description = "Dictionary ID", required = true) @PathVariable String id) {
        lookupService.deactivate(id);
        return ResponseEntity.ok(ApiResponse.ok(SimpleResultResponse.ok()));
    }

    @DeleteMapping("/{id}")
    @CommonApiResponses
    @Operation(summary = "Delete Dictionary Item (Hard Delete)")
    public ResponseEntity<ApiResponse<SimpleResultResponse>> delete(
            @Parameter(description = "Dictionary ID", required = true) @PathVariable String id) {
        lookupService.delete(id);
        return ResponseEntity.ok(ApiResponse.ok(SimpleResultResponse.ok()));
    }
}
