package com.vomattapi.application.controller;

import com.vomattapi.application.dto.common.ApiResponse;
import com.vomattapi.application.dto.lookup.CreateLookupRequest;
import com.vomattapi.application.dto.lookup.LookupDto;
import com.vomattapi.application.dto.lookup.UpdateLookupRequest;
import com.vomattapi.application.service.lookup.LookupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/lookups")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - Lookup", description = "字典表管理 API（Admin）")
@SecurityRequirement(name = "Bearer Authentication")
public class AdminLookupController {

    private final LookupService lookupService;

    @PostMapping
    @Operation(summary = "建立字典項目")
    public ResponseEntity<ApiResponse<LookupDto>> create(
            @Valid @RequestBody CreateLookupRequest request) {
        LookupDto result = lookupService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(result, "字典項目建立成功"));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新字典項目")
    public ResponseEntity<ApiResponse<LookupDto>> update(
            @Parameter(description = "字典 ID", required = true) @PathVariable String id,
            @Valid @RequestBody UpdateLookupRequest request) {
        LookupDto result = lookupService.update(id, request);
        return ResponseEntity.ok(ApiResponse.success(result, "字典項目更新成功"));
    }

    @PatchMapping("/{id}/activate")
    @Operation(summary = "啟用字典項目")
    public ResponseEntity<ApiResponse<Void>> activate(
            @Parameter(description = "字典 ID", required = true) @PathVariable String id) {
        lookupService.activate(id);
        return ResponseEntity.ok(ApiResponse.success(null, "字典項目已啟用"));
    }

    @PatchMapping("/{id}/deactivate")
    @Operation(summary = "停用字典項目（軟刪除）")
    public ResponseEntity<ApiResponse<Void>> deactivate(
            @Parameter(description = "字典 ID", required = true) @PathVariable String id) {
        lookupService.deactivate(id);
        return ResponseEntity.ok(ApiResponse.success(null, "字典項目已停用"));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "刪除字典項目（硬刪除）")
    public ResponseEntity<ApiResponse<Void>> delete(
            @Parameter(description = "字典 ID", required = true) @PathVariable String id) {
        lookupService.delete(id);
        return ResponseEntity.ok(ApiResponse.success(null, "字典項目已刪除"));
    }
}
