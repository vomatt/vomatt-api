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
@Tag(name = "Lookup", description = "Dictionary lookup APIs (Public)")
public class LookupController {

    private final LookupService lookupService;

    @GetMapping("/frontend")
    @Operation(summary = "Get frontend dictionary", description = "Return all active dictionaries marked as frontendUsing, grouped by type")
    public ResponseEntity<ApiResponse<Map<String, List<LookupDto>>>> getFrontendLookups() {
        Map<String, List<LookupDto>> result = lookupService.getFrontendLookups();
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/{lookupType}")
    @Operation(summary = "Get all active dictionary items of a type")
    public ResponseEntity<ApiResponse<List<LookupDto>>> getActiveByType(
            @Parameter(description = "Dictionary type", required = true)
            @PathVariable String lookupType) {
        List<LookupDto> result = lookupService.getActiveByType(lookupType);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/{lookupType}/{lookupKey}")
    @Operation(summary = "Get a single dictionary item")
    public ResponseEntity<ApiResponse<LookupDto>> getByTypeAndKey(
            @Parameter(description = "Dictionary type", required = true) @PathVariable String lookupType,
            @Parameter(description = "Dictionary key", required = true) @PathVariable String lookupKey) {
        LookupDto result = lookupService.getByTypeAndKey(lookupType, lookupKey);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/{lookupType}/children")
    @Operation(summary = "Get child dictionary items", description = "Query child items of a type by parent node")
    public ResponseEntity<ApiResponse<List<LookupDto>>> getChildren(
            @Parameter(description = "Dictionary type", required = true) @PathVariable String lookupType,
            @Parameter(description = "Parent node type", required = true) @RequestParam String parentType,
            @Parameter(description = "Parent node key", required = true) @RequestParam String parentKey) {
        List<LookupDto> result = lookupService.getChildrenByParent(lookupType, parentType, parentKey);
        return ResponseEntity.ok(ApiResponse.success(result));
    }
}
