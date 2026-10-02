package com.vomatt.tags;

import com.vomatt.common.response.ApiResponse;
import com.vomatt.common.response.PageResponse;
import com.vomatt.common.annotation.PublicApiResponse;
import com.vomatt.tags.dto.TagDto;
import com.vomatt.tags.TagService;
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
    @Operation(summary = "Get Popular Tags", description = "Get popular tags sorted by usage count (paginated)")
    public ResponseEntity<ApiResponse<PageResponse<TagDto>>> getPopularTags(
            @PageableDefault(size = 20) Pageable pageable) {
        Page<TagDto> tags = tagService.getPopularTags(pageable);
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.from(tags)));
    }
}
