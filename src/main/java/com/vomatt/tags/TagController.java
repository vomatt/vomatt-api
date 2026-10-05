package com.vomatt.tags;

import com.vomatt.common.response.ApiResponse;
import com.vomatt.common.response.CursorResponse;
import com.vomatt.common.annotation.PublicApiResponse;
import com.vomatt.tags.dto.TagDto;
import com.vomatt.tags.TagService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/tags")
@RequiredArgsConstructor
@Tag(name = "Tag", description = "Tag management public APIs")
public class TagController {

    private final TagService tagService;

    @GetMapping
    @PublicApiResponse
    @Operation(summary = "Get All Tags", description = "Get a list of all available tags in the system")
    public ResponseEntity<ApiResponse<List<TagDto>>> getAllTags() {
        List<TagDto> tags = tagService.getAllTags();
        return ResponseEntity.ok(ApiResponse.ok(tags));
    }

    @GetMapping("/popular")
    @PublicApiResponse
    @Operation(summary = "Get Popular Tags",
            description = "Tags by usage count, most used first (cursor-paged; items may shift while usage changes)")
    public ResponseEntity<ApiResponse<CursorResponse<TagDto>>> getPopularTags(
            @Parameter(description = "上一頁回傳的 nextCursor") @RequestParam(required = false) String cursor,
            @Parameter(description = "每頁筆數（1–50，預設 20）") @RequestParam(required = false) Integer limit) {
        return ResponseEntity.ok(ApiResponse.ok(tagService.getPopularTags(cursor, limit)));
    }
}
