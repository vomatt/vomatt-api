package com.vomatt.lookups.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Create dictionary item request
 */
@Data
@Schema(description = "Create a dictionary item; new items are always active")
public class CreateLookupRequest {

    @NotBlank
    @Size(max = 50)
    @Schema(description = "Dictionary type (trimmed, max 50 characters)", example = "vote_duration")
    private String lookupType;

    @NotBlank
    @Size(max = 50)
    @Schema(description = "Key, unique within the type (trimmed, max 50 characters)", example = "24h")
    private String lookupKey;

    @NotNull
    @Schema(description = "Arbitrary JSON value (string, number, object or array); must not be null",
            example = "{\"label\": \"24 hours\", \"hours\": 24}")
    private Object lookupValue;

    @Schema(description = "Sort position within the type (ascending); defaults to 0", example = "1")
    private Integer seq = 0;

    @Size(max = 50)
    @Schema(description = "Parent item's type, for hierarchical dictionaries", example = "vote_category", nullable = true)
    private String parentType;

    @Size(max = 50)
    @Schema(description = "Parent item's key, for hierarchical dictionaries", example = "life", nullable = true)
    private String parentKey;

    @Size(max = 100)
    @Schema(description = "Free-text description (max 100 characters)", example = "Poll duration presets", nullable = true)
    private String description;

    @Schema(description = "Include the item in the frontend dictionary; defaults to false", example = "true")
    private Boolean frontendUsing = false;
}
