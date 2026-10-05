package com.vomatt.comments.dto;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateCommentRequest {

    @NotBlank(message = "Comment content is required")
    @Size(max = 2000, message = "Comment cannot exceed 2000 characters")
    private String text;

    @Schema(description = "要回覆的留言 ID；回覆「回覆」時會掛在同一則頂層留言下", nullable = true)
    private UUID parentId;
}
