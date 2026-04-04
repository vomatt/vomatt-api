package com.vomattapi.application.dto.user;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Profile response — return all fields when authenticated user queries their own profile
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
