package com.vomatt.tags;

import com.vomatt.common.response.ApiResponse;
import com.vomatt.common.response.CursorResponse;
import com.vomatt.common.annotation.CommonApiResponses;
import com.vomatt.common.annotation.PublicApiResponse;
import com.vomatt.common.config.OpenAPIConfig;
import com.vomatt.common.response.SimpleResultResponse;
import com.vomatt.common.security.UserPrincipal;
import com.vomatt.tags.dto.TagDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/tags")
@RequiredArgsConstructor
@Tag(name = "Tag", description = "Tag APIs: public lists and the caller's followed tags")
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
        return ResponseEntity.ok(ApiResponse.ok(tagService.getAllTags()));
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

    @GetMapping("/following")
    @CommonApiResponses
    @Operation(summary = "List followed Tags",
            description = """
                    **Auth**: required
                    **Precondition**: none
                    **Behavior**: returns the tags the caller follows (`PUT /api/tags/{tagId}/follow`), most recently followed first; \
                    not paginated. `[]` when none
                    **Side effects**: none
                    **Errors**: none specific to this endpoint (only the shared errors below)""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success",
            content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "FollowedTags", value = """
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
    public ResponseEntity<ApiResponse<List<TagDto>>> getFollowedTags(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(tagService.getFollowedTags(principal.userId())));
    }

    @PutMapping("/{tagId}/follow")
    @CommonApiResponses
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success",
            content = @Content(examples = @ExampleObject(value = """
                    {"success":true,"data":{"success":true,"id":null,"status":null,"message":null},"message":null,"errorCode":null,"error":null}""")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Tag does not exist (errorCode `tag.not_found`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(value = """
                            {"success":false,"data":null,"message":"Tag not found","errorCode":"tag.not_found","error":"Tag not found"}""")))
    @Operation(summary = "Follow a Tag", description = """
            **Auth**: required
            **Precondition**: `tagId` is an existing tag
            **Behavior**: from then on `GET /api/votes/following` also lists Open PUBLIC Polls carrying the tag, and the tag shows in \
            `GET /api/tags/following`. Idempotent: following an already followed tag succeeds.
            **Side effects**: none
            **Errors**:
            - 400 `common.bad_request`: `tagId` is not a valid id
            - 404 `tag.not_found`: no tag with this id
            - 404 `user.not_found`: the signed-in account no longer exists""")
    public ResponseEntity<ApiResponse<SimpleResultResponse>> followTag(
            @Parameter(description = "Id of the tag to follow", required = true, example = "0199c3a2-7b5e-7c1d-9a4f-3e2b1d5c6f70")
            @PathVariable String tagId,
            @AuthenticationPrincipal UserPrincipal principal) {
        tagService.followTag(principal.userId(), tagId);
        return ResponseEntity.ok(ApiResponse.ok(SimpleResultResponse.ok()));
    }

    @DeleteMapping("/{tagId}/follow")
    @CommonApiResponses
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success",
            content = @Content(examples = @ExampleObject(value = """
                    {"success":true,"data":{"success":true,"id":null,"status":null,"message":null},"message":null,"errorCode":null,"error":null}""")))
    @Operation(summary = "Unfollow a Tag", description = """
            **Auth**: required
            **Precondition**: none
            **Behavior**: removes the caller's follow of the tag, so Polls that reached `GET /api/votes/following` only through it leave the list. \
            Idempotent: unfollowing a tag that was not followed, or that does not exist, succeeds.
            **Side effects**: none
            **Errors**:
            - 400 `common.bad_request`: `tagId` is not a valid id""")
    public ResponseEntity<ApiResponse<SimpleResultResponse>> unfollowTag(
            @Parameter(description = "Id of the tag to unfollow", required = true, example = "0199c3a2-7b5e-7c1d-9a4f-3e2b1d5c6f70")
            @PathVariable String tagId,
            @AuthenticationPrincipal UserPrincipal principal) {
        tagService.unfollowTag(principal.userId(), tagId);
        return ResponseEntity.ok(ApiResponse.ok(SimpleResultResponse.ok()));
    }
}
