package com.vomattapi.application.controller;

import com.vomattapi.application.dto.response.ApiResponse;
import com.vomattapi.application.dto.response.TagDto;
import com.vomattapi.application.service.TagService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/tags")
@RequiredArgsConstructor
@Tag(name = "Tag", description = "Tag management public APIs")
public class TagController {

    private final TagService tagService;

    @GetMapping
    @Operation(summary = "取得所有標籤", description = "取得系統中所有可用的標籤列表")
    public ResponseEntity<ApiResponse<List<TagDto>>> getAllTags() {
        List<TagDto> tags = tagService.getAllTags();
        return ResponseEntity.ok(ApiResponse.success(tags));
    }

    @GetMapping("/popular")
    @Operation(summary = "取得熱門標籤", description = "依使用次數排序取得熱門標籤（分頁）")
    public ResponseEntity<ApiResponse<Page<TagDto>>> getPopularTags(
            @PageableDefault(size = 20) Pageable pageable) {
        Page<TagDto> tags = tagService.getPopularTags(pageable);
        return ResponseEntity.ok(ApiResponse.success(tags));
    }
}
