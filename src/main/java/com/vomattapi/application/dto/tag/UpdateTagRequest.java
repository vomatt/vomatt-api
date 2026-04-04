package com.vomattapi.application.dto.tag;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateTagRequest {

    @NotBlank(message = "Tag name cannot be empty")
    @Size(max = 30, message = "Tag name maximum 30 characters")
    private String name;

    @Size(max = 50, message = "Slug maximum 50 characters")
    @Pattern(regexp = "^[a-z0-9\\-\\u4e00-\\u9fff\\u3040-\\u309f\\u30a0-\\u30ff]*$",
             message = "Slug allows only lowercase English, numbers, hyphens and CJK characters")
    private String slug;

    @Size(max = 200, message = "Tag description maximum 200 characters")
    private String description;

    private int displayOrder = 0;
}
