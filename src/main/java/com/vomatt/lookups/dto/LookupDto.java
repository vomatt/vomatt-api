package com.vomatt.lookups.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Dictionary table response DTO
 */
@Schema(description = "A dictionary (lookup) item")
public record LookupDto(
        @Schema(description = "Dictionary item ID (UUIDv7)", example = "0199c3a2-7b5e-7c1d-9a4f-3e2b1d5c6f71") String id,
        @Schema(description = "Dictionary type (group name)", example = "vote_duration") String lookupType,
        @Schema(description = "Key, unique within its type", example = "24h") String lookupKey,
        @Schema(description = "Arbitrary JSON value (string, number, object or array); the shape depends on the type",
                example = "{\"label\": \"24 hours\", \"hours\": 24}") Object lookupValue,
        @Schema(description = "Sort position within the type (ascending)", example = "1") Integer seq,
        @Schema(description = "Parent item's type; null for top-level items", example = "vote_category", nullable = true) String parentType,
        @Schema(description = "Parent item's key; null for top-level items", example = "life", nullable = true) String parentKey,
        @Schema(description = "Whether the item is active. Type listings and the frontend dictionary contain only active items; "
                + "only the single-item endpoint can return an inactive one", example = "true") Boolean isActive,
        @Schema(description = "Free-text description; null when not set", example = "Poll duration presets", nullable = true) String description,
        @Schema(description = "Whether the item is included in the frontend dictionary", example = "true") Boolean frontendUsing
) {}
