package com.vomatt.tags.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A tag that can be attached to a Poll")
public class TagDto {
    @Schema(description = "Tag ID (UUIDv7)", example = "0199c3a2-7b5e-7c1d-9a4f-3e2b1d5c6f70")
    private String id;
    @Schema(description = "Display name, at most 30 characters", example = "Technology")
    private String name;
    @Schema(description = "URL-friendly unique identifier", example = "technology")
    private String slug;
    @Schema(description = "Tag description; null when the admin did not set one", example = "Gadgets, software and the internet",
            nullable = true)
    private String description;
    @Schema(description = "Sort position used by the full tag list (ascending)", example = "1")
    private int displayOrder;
    @Schema(description = "Number of Polls currently using this tag; the popular list is ordered by this value", example = "42")
    private int usageCount;
}
