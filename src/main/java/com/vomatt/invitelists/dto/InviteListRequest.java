package com.vomatt.invitelists.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

@Data
@Schema(description = "Body to create or rename an Invite List")
public class InviteListRequest {

    @NotBlank(message = "Invite List name is required")
    @Size(max = 50, message = "Invite List name cannot exceed 50 characters")
    @Schema(description = "Invite List name, up to 50 characters; leading and trailing spaces are trimmed. Names need not be unique",
            example = "Book club")
    private String name;
}
