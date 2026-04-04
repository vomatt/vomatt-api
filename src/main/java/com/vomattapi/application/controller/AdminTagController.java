package com.vomattapi.application.controller;

import com.vomattapi.application.dto.tag.CreateTagRequest;
import com.vomattapi.application.dto.tag.UpdateTagRequest;
import com.vomattapi.application.dto.common.ApiResponse;
import com.vomattapi.application.dto.tag.TagDto;
import com.vomattapi.application.service.tag.TagService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/tags")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin Tag", description = "Admin-only tag management APIs")
public class AdminTagController {

    private final TagService tagService;

    @PostMapping
    @Operation(summary = "建立標籤", description = "管理員建立新標籤")
    public ResponseEntity<ApiResponse<TagDto>> createTag(
            @Valid @RequestBody CreateTagRequest request) {
        TagDto tag = tagService.createTag(request);
        return ResponseEntity.ok(ApiResponse.success(tag, "標籤建立成功"));
    }

    @PutMapping("/{tagId}")
    @Operation(summary = "更新標籤", description = "管理員更新現有標籤")
    public ResponseEntity<ApiResponse<TagDto>> updateTag(
            @Parameter(description = "標籤 ID", required = true)
            @PathVariable String tagId,
            @Valid @RequestBody UpdateTagRequest request) {
        TagDto tag = tagService.updateTag(tagId, request);
        return ResponseEntity.ok(ApiResponse.success(tag, "標籤更新成功"));
    }

    @DeleteMapping("/{tagId}")
    @Operation(summary = "刪除標籤", description = "管理員刪除標籤")
    public ResponseEntity<ApiResponse<Void>> deleteTag(
            @Parameter(description = "標籤 ID", required = true)
            @PathVariable String tagId) {
        tagService.deleteTag(tagId);
        return ResponseEntity.ok(ApiResponse.success(null, "標籤刪除成功"));
    }
}
