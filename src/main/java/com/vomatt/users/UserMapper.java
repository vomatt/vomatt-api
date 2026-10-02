package com.vomatt.users;

import com.vomatt.users.dto.MyProfileResponse;
import com.vomatt.users.dto.UserDto;
import com.vomatt.users.dto.UserProfileResponse;
import com.vomatt.entity.User;
import com.vomatt.repository.UserProfileProjection;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class UserMapper {

    public UserDto toDto(User user) {
        UserDto dto = new UserDto();
        dto.setId(user.getId().toString());
        dto.setUsername(user.getUsername());
        dto.setEmail(user.getEmail());
        dto.setPhoneNumber(user.getPhoneNumber());
        dto.setFirstName(user.getFirstName());
        dto.setLastName(user.getLastName());
        dto.setLocation(user.getLocation());
        dto.setPoints(user.getPoints());
        dto.setMembershipLevel(user.getMembershipLevel());
        dto.setActive(user.isActive());
        dto.setCreatedAt(user.getCreatedAt());
        dto.setLastLoginAt(user.getLastLoginAt());
        return dto;
    }

    /**
     * Convert User entity to profile response (including all fields)
     */
    public MyProfileResponse toMyProfileResponse(User user, int totalPolls, int totalVotes,
                                                  Map<String, Boolean> visibilitySettings) {
        return new MyProfileResponse(
                user.getId().toString(),
                user.getUsername(),
                user.getEmail(),
                user.getPhoneNumber(),
                user.getFirstName(),
                user.getLastName(),
                user.getDisplayName(),
                user.getBio(),
                user.getLocation(),
                user.getPoints(),
                user.getMembershipLevel(),
                user.isActive(),
                user.getLastLoginAt(),
                user.getCreatedAt(),
                totalPolls,
                totalVotes,
                visibilitySettings
        );
    }

    /**
     * Convert Projection to public profile response, mask fields based on visibility settings
     */
    public UserProfileResponse toPublicProfileResponse(UserProfileProjection projection,
                                                        Map<String, Boolean> visibility) {
        return new UserProfileResponse(
                projection.getUsername(),
                projection.getDisplayName(),
                projection.getBio(),
                projection.getCreatedAt(),
                projection.getTotalPolls().intValue(),
                projection.getTotalVotes().intValue(),
                visibility.getOrDefault("email", false) ? projection.getEmail() : null,
                visibility.getOrDefault("firstName", false) ? projection.getFirstName() : null,
                visibility.getOrDefault("lastName", false) ? projection.getLastName() : null,
                visibility.getOrDefault("location", false) ? projection.getLocation() : null,
                visibility.getOrDefault("points", false) ? projection.getPoints() : null,
                visibility.getOrDefault("membershipLevel", false) ? projection.getMembershipLevel() : null
        );
    }
}
