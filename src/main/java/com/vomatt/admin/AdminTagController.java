package com.vomatt.admin;

import com.vomatt.tags.dto.CreateTagRequest;
import com.vomatt.tags.dto.UpdateTagRequest;
import com.vomatt.common.response.ApiResponse;
import com.vomatt.common.response.SimpleResultResponse;
import com.vomatt.common.annotation.CommonApiResponses;
import com.vomatt.tags.dto.TagDto;
import com.vomatt.tags.TagService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
@RequestMapping("/api/admin/tags")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin Tag", description = "Admin-only tag management APIs")
public class AdminTagController {

    private final TagService tagService;

    @PostMapping
    @CommonApiResponses
    @Operation(summary = "Create Tag", description = "Admin creates a new tag")
    public ResponseEntity<ApiResponse<TagDto>> createTag(
            @Valid @RequestBody CreateTagRequest request) {
        TagDto tag = tagService.createTag(request);
        return ResponseEntity.ok(ApiResponse.ok(tag));
    }

    @PutMapping("/{tagId}")
    @CommonApiResponses
    @Operation(summary = "Update Tag", description = "Admin updates an existing tag")
    public ResponseEntity<ApiResponse<TagDto>> updateTag(
            @Parameter(description = "Tag ID", required = true)
            @PathVariable String tagId,
            @Valid @RequestBody UpdateTagRequest request) {
        TagDto tag = tagService.updateTag(tagId, request);
        return ResponseEntity.ok(ApiResponse.ok(tag));
    }

    @DeleteMapping("/{tagId}")
    @CommonApiResponses
    @Operation(summary = "Delete Tag", description = "Admin deletes a tag")
    public ResponseEntity<ApiResponse<SimpleResultResponse>> deleteTag(
            @Parameter(description = "Tag ID", required = true)
            @PathVariable String tagId) {
        tagService.deleteTag(tagId);
        return ResponseEntity.ok(ApiResponse.ok(SimpleResultResponse.ok()));
    }
}
