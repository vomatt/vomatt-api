package com.vomatt.lookups.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Update dictionary item request (all fields are Optional, only update non-null fields)
 */
@Data
@Schema(description = "Update a dictionary item. Despite the PUT verb this is a partial update: "
        + "null or omitted fields are left unchanged, and a field cannot be cleared back to null")
public class UpdateLookupRequest {

    @Size(max = 50)
    @Schema(description = "New dictionary type; null keeps the current one", example = "vote_duration", nullable = true)
    private String lookupType;

    @Size(max = 50)
    @Schema(description = "New key; null keeps the current one", example = "48h", nullable = true)
    private String lookupKey;

    @Schema(description = "New JSON value; null keeps the current one", example = "{\"label\": \"48 hours\", \"hours\": 48}", nullable = true)
    private Object lookupValue;

    @Schema(description = "New sort position; null keeps the current one", example = "2", nullable = true)
    private Integer seq;

    @Size(max = 50)
    @Schema(description = "New parent type; null keeps the current one", example = "vote_category", nullable = true)
    private String parentType;

    @Size(max = 50)
    @Schema(description = "New parent key; null keeps the current one", example = "life", nullable = true)
    private String parentKey;

    @Size(max = 100)
    @Schema(description = "New description; null keeps the current one", example = "Poll duration presets", nullable = true)
    private String description;

    @Schema(description = "New frontend-dictionary flag; null keeps the current one", example = "true", nullable = true)
    private Boolean frontendUsing;
}
