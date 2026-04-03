package com.vomattapi.application.controller;

import com.vomattapi.application.dto.common.ApiResponse;
import com.vomattapi.application.dto.lookup.LookupDto;
import com.vomattapi.application.service.lookup.LookupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/lookups")
@RequiredArgsConstructor
@Tag(name = "Lookup", description = "字典表查詢 API（公開）")
public class LookupController {

    private final LookupService lookupService;

    @GetMapping("/frontend")
    @Operation(summary = "取得前端用字典", description = "回傳所有標記為 frontendUsing 的 active 字典，依 type 分組")
    public ResponseEntity<ApiResponse<Map<String, List<LookupDto>>>> getFrontendLookups() {
        Map<String, List<LookupDto>> result = lookupService.getFrontendLookups();
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/{lookupType}")
    @Operation(summary = "取得某 type 的所有 active 字典項目")
    public ResponseEntity<ApiResponse<List<LookupDto>>> getActiveByType(
            @Parameter(description = "字典類型", required = true)
            @PathVariable String lookupType) {
        List<LookupDto> result = lookupService.getActiveByType(lookupType);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/{lookupType}/{lookupKey}")
    @Operation(summary = "取得單筆字典項目")
    public ResponseEntity<ApiResponse<LookupDto>> getByTypeAndKey(
            @Parameter(description = "字典類型", required = true) @PathVariable String lookupType,
            @Parameter(description = "字典 key", required = true) @PathVariable String lookupKey) {
        LookupDto result = lookupService.getByTypeAndKey(lookupType, lookupKey);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/{lookupType}/children")
    @Operation(summary = "取得子字典項目", description = "依父節點查詢某 type 下的子項目")
    public ResponseEntity<ApiResponse<List<LookupDto>>> getChildren(
            @Parameter(description = "字典類型", required = true) @PathVariable String lookupType,
            @Parameter(description = "父節點 type", required = true) @RequestParam String parentType,
            @Parameter(description = "父節點 key", required = true) @RequestParam String parentKey) {
        List<LookupDto> result = lookupService.getChildrenByParent(lookupType, parentType, parentKey);
        return ResponseEntity.ok(ApiResponse.success(result));
    }
}
