package com.vomattapi.domain.user.repository;

import java.time.LocalDateTime;

/**
 * Projection 介面：一次查詢取得 User 基本資料與統計數字，避免多次查詢
 */
public interface UserProfileProjection {
    String getUsername();
    String getDisplayName();
    String getBio();
    LocalDateTime getCreatedAt();
    Long getTotalPolls();
    Long getTotalVotes();
}
