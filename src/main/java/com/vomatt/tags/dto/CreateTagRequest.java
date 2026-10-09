package com.vomatt.tags.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "Create a tag")
public class CreateTagRequest {

    @NotBlank(message = "Tag name cannot be empty")
    @Size(max = 30, message = "Tag name maximum 30 characters")
    @Schema(description = "Tag name (trimmed, unique, max 30 characters)", example = "Technology")
    private String name;

    @Size(max = 50, message = "Slug maximum 50 characters")
    @Pattern(regexp = "^[a-z0-9\\-\\u4e00-\\u9fff\\u3040-\\u309f\\u30a0-\\u30ff]*$",
             message = "Slug allows only lowercase English, numbers, hyphens and CJK characters")
    @Schema(description = "Unique slug (max 50 characters; lowercase letters, digits, hyphens and CJK only). "
            + "When null or blank it is generated from the name", example = "technology", nullable = true)
    private String slug;

    @Size(max = 200, message = "Tag description maximum 200 characters")
    @Schema(description = "Tag description (max 200 characters)", example = "Gadgets, software and the internet", nullable = true)
    private String description;

    @Schema(description = "Sort position for the full tag list (ascending); defaults to 0", example = "1")
    private int displayOrder = 0;
}
