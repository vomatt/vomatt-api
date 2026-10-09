package com.vomatt.comments.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.Getter;

import java.time.OffsetDateTime;

@Data
@Schema(description = "A Comment (top-level) or a Reply (parentId set) on a Poll")
public class CommentDto {

    @Schema(description = "Comment ID (UUIDv7)", example = "0199c3a2-7b1e-7c4d-9a10-3f5e8d2b6a41")
    private String id;

    @Schema(description = "ID of the Poll this Comment belongs to", example = "0199c2f0-4d3a-7e8b-b6c2-1a9f0e7d5c33")
    private String voteId;

    @Schema(description = "ID of the author. Null only on a deleted placeholder (`deleted` = true)",
            example = "0199b8e4-1c2d-7f60-8a3b-6d4e2f1a9b05", nullable = true)
    private String userId;

    @Schema(description = "Username of the author. Null only on a deleted placeholder (`deleted` = true)",
            example = "alice", nullable = true)
    private String author;

    @Schema(description = "Comment text (max 2000 characters). Null only on a deleted placeholder (`deleted` = true)",
            example = "I would pick option B, it is the cheaper one.", nullable = true)
    private String text;

    @Schema(description = "Creation time (ISO-8601 with offset)", example = "2026-10-09T12:34:56+08:00")
    private OffsetDateTime createdAt;

    @Schema(description = "Last modification time (ISO-8601 with offset). Equals createdAt until the text is edited",
            example = "2026-10-09T12:40:02+08:00")
    private OffsetDateTime updatedAt;

    @Getter(onMethod_ = @Schema(description = "True once the text was edited after posting (updatedAt is more than 1 second after createdAt)",
            example = "false"))
    private boolean isEdited;

    @Schema(description = "Number of likes", example = "3")
    private long likeCount;

    @Getter(onMethod_ = @Schema(description = "Whether the signed-in user liked this Comment. Always false when the request is not authenticated",
            example = "false"))
    private boolean isLikedByCurrentUser;

    @Schema(description = "ID of the top-level Comment this Reply belongs to. Null for a top-level Comment",
            example = "0199c3a2-7b1e-7c4d-9a10-3f5e8d2b6a41", nullable = true)
    private String parentId;

    @Schema(description = "Number of non-deleted Replies. Only filled on top-level Comments in the Poll's Comment list; "
            + "0 on Replies and on responses of create / update", example = "2")
    private long replyCount;

    @Getter(onMethod_ = @Schema(description = "True for a deleted top-level Comment kept as a placeholder because it still has Replies; "
            + "userId, author and text are then null. Deleted Comments without Replies are not returned at all",
            example = "false"))
    private boolean isDeleted;
}
