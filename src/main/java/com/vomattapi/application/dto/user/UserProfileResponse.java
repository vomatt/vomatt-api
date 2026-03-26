package com.vomattapi.application.dto.user;

import java.time.LocalDateTime;

public record UserProfileResponse(
        String username,
        String displayName,
        String bio,
        LocalDateTime joinedAt,
        int totalPolls,
        int totalVotes
) {}
