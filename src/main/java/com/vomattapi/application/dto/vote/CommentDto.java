package com.vomattapi.application.dto.vote;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CommentDto {

    private String id;
    private String voteId;
    private String userId;
    private String author;
    private String text;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private boolean isEdited;
    private long likeCount;
    private boolean isLikedByCurrentUser;
}
