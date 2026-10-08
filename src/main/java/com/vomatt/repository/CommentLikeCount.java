package com.vomatt.repository;

import java.util.UUID;

/**
 * Projection 介面：各留言的按讚數
 */
public interface CommentLikeCount {
    UUID getCommentId();
    Long getCount();
}
