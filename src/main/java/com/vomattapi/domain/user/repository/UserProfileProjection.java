package com.vomattapi.domain.user.repository;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Projection 介面：一次查詢取得 User 基本資料與統計數字，避免多次查詢
 */
public interface UserProfileProjection {
    UUID getId();
    String getUsername();
    String getDisplayName();
    String getBio();
    LocalDateTime getCreatedAt();
    Long getTotalPolls();
    Long getTotalVotes();
    // 可控制顯示的擴展欄位
    String getEmail();
    String getFirstName();
    String getLastName();
    String getLocation();
    Integer getPoints();
    String getMembershipLevel();
}
