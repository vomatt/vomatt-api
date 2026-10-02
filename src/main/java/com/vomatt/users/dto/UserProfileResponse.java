package com.vomatt.users.dto;

import java.time.OffsetDateTime;

public record UserProfileResponse(
        String username,
        String displayName,
        String bio,
        OffsetDateTime joinedAt,
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
