package com.vomatt.comments.dto;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateCommentRequest {

    @Schema(description = "Comment text, not blank, max 2000 characters", example = "I would pick option B, it is the cheaper one.")
    @NotBlank(message = "Comment content is required")
    @Size(max = 2000, message = "Comment cannot exceed 2000 characters")
    private String text;

    @Schema(description = "ID of the Comment or Reply to answer, in the same Poll and not deleted. Omit to post a top-level Comment. "
            + "Replying to a Reply attaches the new Reply to the same top-level Comment (one level deep)",
            example = "0199c3a2-7b1e-7c4d-9a10-3f5e8d2b6a41", nullable = true)
    private UUID parentId;
}
