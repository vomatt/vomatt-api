package com.vomatt.comments.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class CommentDto {

    private String id;
    private String voteId;
    private String userId;
    private String author;
    private String text;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private boolean isEdited;
    private long likeCount;
    private boolean isLikedByCurrentUser;

    @Schema(description = "所屬頂層留言 ID；頂層留言為 null", nullable = true)
    private String parentId;

    @Schema(description = "回覆數（僅頂層留言，不含已刪除）")
    private long replyCount;

    @Schema(description = "已刪除但仍有回覆的佔位留言；此時 userId、author、text 為 null")
    private boolean isDeleted;
}
