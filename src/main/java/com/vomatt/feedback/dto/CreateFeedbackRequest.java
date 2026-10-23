package com.vomatt.feedback.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateFeedbackRequest {

    @Schema(description = "Feedback text, not blank, max 2000 characters", example = "Please add dark mode.")
    @NotBlank(message = "Feedback content is required")
    @Size(max = 2000, message = "Feedback cannot exceed 2000 characters")
    private String content;
}
