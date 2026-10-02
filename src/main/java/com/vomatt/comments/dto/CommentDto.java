package com.vomatt.comments.dto;

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
}
