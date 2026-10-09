package com.vomatt.admin;

import com.vomatt.tags.dto.CreateTagRequest;
import com.vomatt.tags.dto.UpdateTagRequest;
import com.vomatt.common.response.ApiResponse;
import com.vomatt.common.response.SimpleResultResponse;
import com.vomatt.common.annotation.CommonApiResponses;
import com.vomatt.common.config.OpenAPIConfig;
import com.vomatt.tags.dto.TagDto;
import com.vomatt.tags.TagService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
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
    @Operation(summary = "Create Tag",
            description = """
                    **Auth**: admin (role ADMIN; 403 `common.forbidden` otherwise)
                    **Precondition**: caller holds role ADMIN
                    **Behavior**: creates a tag. `name` is trimmed; `slug` defaults to a slug generated from the name (lowercase, spaces to hyphens, unsupported characters dropped). Returns 200 with the created tag, `usageCount` 0
                    **Side effects**: a new tag becomes visible in the public tag lists
                    **Errors**:
                    - 400 `common.bad_request`: `slug` is omitted and no valid slug can be generated from `name`
                    - 409 `tag.name.exists`: another tag already has this name
                    - 409 `tag.slug.exists`: another tag already has this slug""")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
            examples = @ExampleObject(name = "CreateTag", value = """
                            {
                              "name": "Technology",
                              "slug": "technology",
                              "description": "Gadgets, software and the internet",
                              "displayOrder": 1
                            }""")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Tag created",
            content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "CreatedTag", value = """
                    {
                      "success": true,
                      "data": {
                        "id": "0199c3a2-7b5e-7c1d-9a4f-3e2b1d5c6f70",
                        "name": "Technology",
                        "slug": "technology",
                        "description": "Gadgets, software and the internet",
                        "displayOrder": 1,
                        "usageCount": 0
                      },
                      "message": null,
                      "errorCode": null,
                      "error": null
                    }""")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "`slug` cannot be generated from the name (`common.bad_request`)",
            content = @Content(mediaType = "application/json", schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "BadRequest", value = """
                            {
                              "success": false,
                              "data": null,
                              "message": "Invalid request",
                              "errorCode": "common.bad_request",
                              "error": "Invalid request"
                            }""")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Tag name already exists (`tag.name.exists`) or slug already exists (`tag.slug.exists`)",
            content = @Content(mediaType = "application/json", schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "TagNameExists", value = """
                            {
                              "success": false,
                              "data": null,
                              "message": "Tag name already exists: Technology",
                              "errorCode": "tag.name.exists",
                              "error": "Tag name already exists: Technology"
                            }""")))
    public ResponseEntity<ApiResponse<TagDto>> createTag(
            @Valid @RequestBody CreateTagRequest request) {
        TagDto tag = tagService.createTag(request);
        return ResponseEntity.ok(ApiResponse.ok(tag));
    }

    @PutMapping("/{tagId}")
    @CommonApiResponses
    @Operation(summary = "Update Tag",
            description = """
                    **Auth**: admin (role ADMIN; 403 `common.forbidden` otherwise)
                    **Precondition**: caller holds role ADMIN; the tag exists
                    **Behavior**: replaces name, slug, description and displayOrder with the request values (full update: an omitted `description` becomes null and an omitted `displayOrder` becomes 0). `slug` is regenerated from `name` when null or blank, even if the tag already had a custom slug
                    **Side effects**: the change is visible in the public tag lists; Polls keep their association with the tag
                    **Errors**:
                    - 400 `common.bad_request`: `tagId` is not a valid UUID, or no valid slug can be generated from `name`
                    - 404 `tag.not_found`: tag does not exist
                    - 409 `tag.name.exists`: another tag already has this name
                    - 409 `tag.slug.exists`: another tag already has this slug""")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
            examples = @ExampleObject(name = "UpdateTag", value = """
                            {
                              "name": "Technology",
                              "slug": "technology",
                              "description": "Gadgets, software and the internet",
                              "displayOrder": 1
                            }""")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Tag updated",
            content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "UpdatedTag", value = """
                    {
                      "success": true,
                      "data": {
                        "id": "0199c3a2-7b5e-7c1d-9a4f-3e2b1d5c6f70",
                        "name": "Technology",
                        "slug": "technology",
                        "description": "Gadgets, software and the internet",
                        "displayOrder": 1,
                        "usageCount": 0
                      },
                      "message": null,
                      "errorCode": null,
                      "error": null
                    }""")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Malformed `tagId`, or `slug` cannot be generated (`common.bad_request`)",
            content = @Content(mediaType = "application/json", schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "BadRequest", value = """
                            {
                              "success": false,
                              "data": null,
                              "message": "Invalid request",
                              "errorCode": "common.bad_request",
                              "error": "Invalid request"
                            }""")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Tag not found (`tag.not_found`)",
            content = @Content(mediaType = "application/json", schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "TagNotFound", value = """
                            {
                              "success": false,
                              "data": null,
                              "message": "Tag not found",
                              "errorCode": "tag.not_found",
                              "error": "Tag not found"
                            }""")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Tag name already exists (`tag.name.exists`) or slug already exists (`tag.slug.exists`)",
            content = @Content(mediaType = "application/json", schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "TagNameExists", value = """
                            {
                              "success": false,
                              "data": null,
                              "message": "Tag name already exists: Technology",
                              "errorCode": "tag.name.exists",
                              "error": "Tag name already exists: Technology"
                            }""")))
    public ResponseEntity<ApiResponse<TagDto>> updateTag(
            @Parameter(description = "Tag ID (UUIDv7)", required = true)
            @PathVariable String tagId,
            @Valid @RequestBody UpdateTagRequest request) {
        TagDto tag = tagService.updateTag(tagId, request);
        return ResponseEntity.ok(ApiResponse.ok(tag));
    }

    @DeleteMapping("/{tagId}")
    @CommonApiResponses
    @Operation(summary = "Delete Tag",
            description = """
                    **Auth**: admin (role ADMIN; 403 `common.forbidden` otherwise)
                    **Precondition**: caller holds role ADMIN; the tag exists and is not used by any Poll
                    **Behavior**: hard-deletes the tag; returns `{success: true}`
                    **Side effects**: the tag disappears from the public tag lists
                    **Errors**:
                    - 400 `common.bad_request`: `tagId` is not a valid UUID
                    - 404 `tag.not_found`: tag does not exist
                    - 409 `tag.in_use`: at least one Poll still references the tag""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Tag deleted",
            content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Deleted", value = """
                    {
                      "success": true,
                      "data": {
                        "success": true,
                        "id": null,
                        "status": null,
                        "message": null
                      },
                      "message": null,
                      "errorCode": null,
                      "error": null
                    }""")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Malformed ID in the path (`common.bad_request`)",
            content = @Content(mediaType = "application/json", schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "BadRequest", value = """
                            {
                              "success": false,
                              "data": null,
                              "message": "Invalid request",
                              "errorCode": "common.bad_request",
                              "error": "Invalid request"
                            }""")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Tag not found (`tag.not_found`)",
            content = @Content(mediaType = "application/json", schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "TagNotFound", value = """
                            {
                              "success": false,
                              "data": null,
                              "message": "Tag not found",
                              "errorCode": "tag.not_found",
                              "error": "Tag not found"
                            }""")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Tag is referenced by a Poll (`tag.in_use`)",
            content = @Content(mediaType = "application/json", schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "TagInUse", value = """
                            {
                              "success": false,
                              "data": null,
                              "message": "Tag is in use and cannot be deleted",
                              "errorCode": "tag.in_use",
                              "error": "Tag is in use and cannot be deleted"
                            }""")))
    public ResponseEntity<ApiResponse<SimpleResultResponse>> deleteTag(
            @Parameter(description = "Tag ID (UUIDv7)", required = true)
            @PathVariable String tagId) {
        tagService.deleteTag(tagId);
        return ResponseEntity.ok(ApiResponse.ok(SimpleResultResponse.ok()));
    }
}
