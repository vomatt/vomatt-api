package com.vomattapi.application.dto.user;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 個人資料回應 — 認證使用者查詢自己時回傳所有欄位
 */
public record MyProfileResponse(
        String id,
        String username,
        String email,
        String phoneNumber,
        String firstName,
        String lastName,
        String displayName,
        String bio,
        String location,
        int points,
        String membershipLevel,
        boolean active,
        LocalDateTime lastLoginAt,
        LocalDateTime joinedAt,
        int totalPolls,
        int totalVotes,
        Map<String, Boolean> visibilitySettings
) {}
