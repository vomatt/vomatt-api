package com.vomattapi.application.dto.vote;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateCommentRequest {

    @NotBlank(message = "Comment content is required")
    @Size(max = 2000, message = "Comment cannot exceed 2000 characters")
    private String text;
}
