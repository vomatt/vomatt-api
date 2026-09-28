package com.vomattapi.application.controller;

import com.vomattapi.application.dto.common.ApiResponse;
import com.vomattapi.application.dto.lookup.CreateLookupRequest;
import com.vomattapi.application.dto.lookup.LookupDto;
import com.vomattapi.application.dto.lookup.UpdateLookupRequest;
import com.vomattapi.application.service.lookup.LookupService;
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
@RequestMapping("/api/v1/admin/lookups")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - Lookup", description = "Dictionary management APIs (Admin)")
public class AdminLookupController {

    private final LookupService lookupService;

    @PostMapping
    @Operation(summary = "Create Dictionary Item")
    public ResponseEntity<ApiResponse<LookupDto>> create(
            @Valid @RequestBody CreateLookupRequest request) {
        LookupDto result = lookupService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(result, "Dictionary item created successfully"));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update Dictionary Item")
    public ResponseEntity<ApiResponse<LookupDto>> update(
            @Parameter(description = "Dictionary ID", required = true) @PathVariable String id,
            @Valid @RequestBody UpdateLookupRequest request) {
        LookupDto result = lookupService.update(id, request);
        return ResponseEntity.ok(ApiResponse.success(result, "Dictionary item updated successfully"));
    }

    @PatchMapping("/{id}/activate")
    @Operation(summary = "Activate Dictionary Item")
    public ResponseEntity<ApiResponse<Void>> activate(
            @Parameter(description = "Dictionary ID", required = true) @PathVariable String id) {
        lookupService.activate(id);
        return ResponseEntity.ok(ApiResponse.success(null, "Dictionary item activated"));
    }

    @PatchMapping("/{id}/deactivate")
    @Operation(summary = "Deactivate Dictionary Item (Soft Delete)")
    public ResponseEntity<ApiResponse<Void>> deactivate(
            @Parameter(description = "Dictionary ID", required = true) @PathVariable String id) {
        lookupService.deactivate(id);
        return ResponseEntity.ok(ApiResponse.success(null, "Dictionary item deactivated"));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete Dictionary Item (Hard Delete)")
    public ResponseEntity<ApiResponse<Void>> delete(
            @Parameter(description = "Dictionary ID", required = true) @PathVariable String id) {
        lookupService.delete(id);
        return ResponseEntity.ok(ApiResponse.success(null, "Dictionary item deleted"));
    }
}
