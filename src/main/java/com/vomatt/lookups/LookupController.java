package com.vomatt.lookups;

import com.vomatt.common.response.ApiResponse;
import com.vomatt.common.annotation.CommonApiResponses;
import com.vomatt.common.config.OpenAPIConfig;
import com.vomatt.lookups.dto.LookupDto;
import com.vomatt.lookups.LookupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/lookups")
@RequiredArgsConstructor
@Tag(name = "Lookup", description = "Dictionary lookup APIs")
public class LookupController {

    private final LookupService lookupService;

    @GetMapping("/frontend")
    @CommonApiResponses
    @Operation(summary = "Get frontend dictionary",
            description = """
                    **Auth**: required
                    **Precondition**: none
                    **Behavior**: returns all active items flagged `frontendUsing`, grouped by `lookupType` (map key = type, ordered by `seq`). \
                    The result is cached server-side for 2 hours and evicted whenever an admin changes a frontend item, so clients may cache it for the session. \
                    A type with no matching items is absent from the map
                    **Side effects**: none
                    **Errors**: none specific to this endpoint (only the shared errors below)""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success",
            content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "FrontendLookups", value = """
                    {
                      "success": true,
                      "data": {
                        "vote_duration": [
                          {
                            "id": "0199c3a2-7b5e-7c1d-9a4f-3e2b1d5c6f71",
                            "lookupType": "vote_duration",
                            "lookupKey": "24h",
                            "lookupValue": { "label": "24 hours", "hours": 24 },
                            "seq": 1,
                            "parentType": null,
                            "parentKey": null,
                            "isActive": true,
                            "description": "Poll duration presets",
                            "frontendUsing": true
                          }
                        ]
                      },
                      "message": null,
                      "errorCode": null,
                      "error": null
                    }""")))
    public ResponseEntity<ApiResponse<Map<String, List<LookupDto>>>> getFrontendLookups() {
        Map<String, List<LookupDto>> result = lookupService.getFrontendLookups();
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @GetMapping("/{lookupType}")
    @CommonApiResponses
    @Operation(summary = "List active dictionary items of a type",
            description = """
                    **Auth**: required
                    **Precondition**: none
                    **Behavior**: returns the active items of `lookupType` ordered by `seq`; an unknown type yields an empty list, not 404. \
                    Cached server-side for 2 hours per type and evicted when an admin changes an item of that type
                    **Side effects**: none
                    **Errors**: none specific to this endpoint (only the shared errors below)""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success",
            content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "LookupsByType", value = """
                    {
                      "success": true,
                      "data": [
                        {
                          "id": "0199c3a2-7b5e-7c1d-9a4f-3e2b1d5c6f71",
                          "lookupType": "vote_duration",
                          "lookupKey": "24h",
                          "lookupValue": { "label": "24 hours", "hours": 24 },
                          "seq": 1,
                          "parentType": null,
                          "parentKey": null,
                          "isActive": true,
                          "description": "Poll duration presets",
                          "frontendUsing": true
                        }
                      ],
                      "message": null,
                      "errorCode": null,
                      "error": null
                    }""")))
    public ResponseEntity<ApiResponse<List<LookupDto>>> getActiveByType(
            @Parameter(description = "Dictionary type", required = true, example = "vote_duration")
            @PathVariable String lookupType) {
        List<LookupDto> result = lookupService.getActiveByType(lookupType);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @GetMapping("/{lookupType}/{lookupKey}")
    @CommonApiResponses
    @Operation(summary = "Get a single dictionary item",
            description = """
                    **Auth**: required
                    **Precondition**: none
                    **Behavior**: returns the item with the given type and key, including inactive items (check `isActive`). Not cached
                    **Side effects**: none
                    **Errors**:
                    - 404 `lookup.not_found`: no item with this type and key""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success",
            content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Lookup", value = """
                    {
                      "success": true,
                      "data": {
                        "id": "0199c3a2-7b5e-7c1d-9a4f-3e2b1d5c6f71",
                        "lookupType": "vote_duration",
                        "lookupKey": "24h",
                        "lookupValue": { "label": "24 hours", "hours": 24 },
                        "seq": 1,
                        "parentType": null,
                        "parentKey": null,
                        "isActive": true,
                        "description": "Poll duration presets",
                        "frontendUsing": true
                      },
                      "message": null,
                      "errorCode": null,
                      "error": null
                    }""")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Dictionary item not found (`lookup.not_found`)",
            content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/" + OpenAPIConfig.ERROR_SCHEMA),
                    examples = @ExampleObject(name = "LookupNotFound", value = """
                            {
                              "success": false,
                              "data": null,
                              "message": "Dictionary item not found",
                              "errorCode": "lookup.not_found",
                              "error": "Dictionary item not found"
                            }""")))
    public ResponseEntity<ApiResponse<LookupDto>> getByTypeAndKey(
            @Parameter(description = "Dictionary type", required = true, example = "vote_duration") @PathVariable String lookupType,
            @Parameter(description = "Dictionary key", required = true, example = "24h") @PathVariable String lookupKey) {
        LookupDto result = lookupService.getByTypeAndKey(lookupType, lookupKey);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @GetMapping("/{lookupType}/children")
    @CommonApiResponses
    @Operation(summary = "List child dictionary items",
            description = """
                    **Auth**: required
                    **Precondition**: none
                    **Behavior**: returns the active items of `lookupType` whose parent is (`parentType`, `parentKey`), ordered by `seq`; \
                    no match yields an empty list. Not cached
                    **Side effects**: none
                    **Errors**:
                    - 400 `common.missing_param`: `parentType` or `parentKey` is missing""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success",
            content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "ChildLookups", value = """
                    {
                      "success": true,
                      "data": [
                        {
                          "id": "0199c3a2-7b5e-7c1d-9a4f-3e2b1d5c6f72",
                          "lookupType": "vote_subcategory",
                          "lookupKey": "food",
                          "lookupValue": { "label": "Food" },
                          "seq": 1,
                          "parentType": "vote_category",
                          "parentKey": "life",
                          "isActive": true,
                          "description": null,
                          "frontendUsing": true
                        }
                      ],
                      "message": null,
                      "errorCode": null,
                      "error": null
                    }""")))
    public ResponseEntity<ApiResponse<List<LookupDto>>> getChildren(
            @Parameter(description = "Dictionary type of the children", required = true, example = "vote_subcategory") @PathVariable String lookupType,
            @Parameter(description = "Parent item's type", required = true, example = "vote_category") @RequestParam String parentType,
            @Parameter(description = "Parent item's key", required = true, example = "life") @RequestParam String parentKey) {
        List<LookupDto> result = lookupService.getChildrenByParent(lookupType, parentType, parentKey);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }
}
