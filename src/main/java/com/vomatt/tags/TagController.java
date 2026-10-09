package com.vomatt.tags;

import com.vomatt.common.response.ApiResponse;
import com.vomatt.common.response.CursorResponse;
import com.vomatt.common.annotation.PublicApiResponse;
import com.vomatt.tags.dto.TagDto;
import com.vomatt.tags.TagService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
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
@Tag(name = "Tag", description = "Public tag APIs")
public class TagController {

    private final TagService tagService;

    @GetMapping
    @PublicApiResponse
    @Operation(summary = "List all Tags",
            description = """
                    **Auth**: public
                    **Precondition**: none
                    **Behavior**: returns every tag ordered by `displayOrder` ascending; not paginated
                    **Side effects**: none
                    **Errors**: none specific to this endpoint (only the shared errors below)""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success",
            content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Tags", value = """
                    {
                      "success": true,
                      "data": [
                        {
                          "id": "0199c3a2-7b5e-7c1d-9a4f-3e2b1d5c6f70",
                          "name": "Technology",
                          "slug": "technology",
                          "description": "Gadgets, software and the internet",
                          "displayOrder": 1,
                          "usageCount": 42
                        }
                      ],
                      "message": null,
                      "errorCode": null,
                      "error": null
                    }""")))
    public ResponseEntity<ApiResponse<List<TagDto>>> getAllTags() {
        List<TagDto> tags = tagService.getAllTags();
        return ResponseEntity.ok(ApiResponse.ok(tags));
    }

    @GetMapping("/popular")
    @PublicApiResponse
    @Operation(summary = "List popular Tags",
            description = """
                    **Auth**: public
                    **Precondition**: none
                    **Behavior**: cursor-paged tags ordered by `usageCount` descending; items may shift between pages while usage changes. \
                    `limit` is clamped to 1-50 (default 20); `nextCursor` is null on the last page
                    **Side effects**: none
                    **Errors**:
                    - 400 `common.cursor_invalid`: `cursor` is malformed or was issued by another list""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success",
            content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "PopularTags", value = """
                    {
                      "success": true,
                      "data": {
                        "items": [
                          {
                            "id": "0199c3a2-7b5e-7c1d-9a4f-3e2b1d5c6f70",
                            "name": "Technology",
                            "slug": "technology",
                            "description": "Gadgets, software and the internet",
                            "displayOrder": 1,
                            "usageCount": 42
                          }
                        ],
                        "nextCursor": "MDE5OWMzYTItN2I1ZS03YzFkLTlhNGYtM2UyYjFkNWM2ZjcwfDQy"
                      },
                      "message": null,
                      "errorCode": null,
                      "error": null
                    }""")))
    public ResponseEntity<ApiResponse<CursorResponse<TagDto>>> getPopularTags(
            @Parameter(description = "`nextCursor` returned by the previous page; omit for the first page") @RequestParam(required = false) String cursor,
            @Parameter(description = "Page size, 1-50 (default 20); out-of-range values are clamped") @RequestParam(required = false) Integer limit) {
        return ResponseEntity.ok(ApiResponse.ok(tagService.getPopularTags(cursor, limit)));
    }
}
