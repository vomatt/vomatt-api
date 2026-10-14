package com.vomatt.groups.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

@Data
@Schema(description = "Body to create or rename a Group")
public class GroupRequest {

    @NotBlank(message = "Group name is required")
    @Size(max = 50, message = "Group name cannot exceed 50 characters")
    @Schema(description = "Group name, up to 50 characters; leading and trailing spaces are trimmed. Names need not be unique",
            example = "Book club")
    private String name;
}
