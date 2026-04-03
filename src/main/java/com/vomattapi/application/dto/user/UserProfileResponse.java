package com.vomattapi.application.dto.user;

import java.time.LocalDateTime;

public record UserProfileResponse(
        String username,
        String displayName,
        String bio,
        LocalDateTime joinedAt,
        int totalPolls,
        int totalVotes,
        // 可控制顯示的欄位 — 隱藏時為 null
        String email,
        String firstName,
        String lastName,
        String location,
        Integer points,
        String membershipLevel
) {}
