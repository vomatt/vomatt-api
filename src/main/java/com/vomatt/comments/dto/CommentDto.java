package com.vomatt.comments.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.List;

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

    @Schema(description = "Avatar of the author as `bot:<kind>` (the bot cast). Older accounts may hold an image URL; null if never set, and on a deleted placeholder (`deleted` = true)",
            example = "bot:boba", nullable = true)
    private String authorAvatarUrl;

    @Schema(description = "Comment text (max 2000 characters). Null only on a deleted placeholder (`deleted` = true)",
            example = "I would pick option B, it is the cheaper one.", nullable = true)
    private String text;

    @Schema(description = "Users the text @mentions, in order of first appearance: the handles the server resolved when the "
            + "Comment was written (at most the first `comment_max_mentions` distinct handles, default 10), less any user renamed "
            + "since. Link exactly these `@username` handles (case-insensitive); others, unknown or over the cap, are plain text. "
            + "[] on a deleted placeholder")
    private List<MentionDto> mentions = List.of();

    @Schema(description = "Creation time (ISO-8601 with offset)", example = "2026-10-09T12:34:56+08:00")
    private OffsetDateTime createdAt;

    @Schema(description = "Last modification time (ISO-8601 with offset). Equals createdAt until the text is edited (a soft delete also moves it forward)",
            example = "2026-10-09T12:40:02+08:00")
    private OffsetDateTime updatedAt;

    @Getter(onMethod_ = @Schema(description = "True once the text was edited after posting (updatedAt is after createdAt). Always false for a deleted placeholder",
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

    @Schema(description = "First Replies under this Comment, oldest first, at most 2, filtered like the Reply list. "
            + "Only filled on top-level Comments in the Poll's Comment list; [] on Replies and on responses of create / update")
    private List<CommentDto> replies = List.of();

    @Schema(description = "Cursor for `GET /api/votes/{voteId}/comments/{commentId}/replies` that continues right after the "
            + "last of `replies`, so no Reply is listed twice. Null when `replies` holds every Reply (replyCount equals its length)",
            example = "UkVQTElFU3wwMTk5YzNhMi03YjFlLTdjNGQtOWExMC0zZjVlOGQyYjZhNDF8MjAyNi0xMC0wOVQwNDozNDo1Nlo", nullable = true)
    private String repliesNextCursor;

    @Getter(onMethod_ = @Schema(description = "True for a deleted top-level Comment kept as a placeholder because it still has Replies; "
            + "userId, author, authorAvatarUrl and text are then null. Deleted Comments without Replies are not returned at all",
            example = "false"))
    private boolean isDeleted;
}
