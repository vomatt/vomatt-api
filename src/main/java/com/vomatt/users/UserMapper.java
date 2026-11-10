package com.vomatt.users;

import com.vomatt.users.dto.FollowUserDto;
import com.vomatt.users.dto.MyProfileResponse;
import com.vomatt.users.dto.UserDto;
import com.vomatt.users.dto.UserProfileResponse;
import com.vomatt.entity.User;
import com.vomatt.repository.UserProfileProjection;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class UserMapper {

    /**
     * Convert User entity to search result item; displayName is masked by the owner's visibility setting
     */
    public UserDto toSearchResult(User user, boolean displayNameVisible) {
        return new UserDto(
                user.getId().toString(),
                user.getUsername(),
                displayNameVisible ? user.getDisplayName() : null,
                user.getAvatarUrl(),
                user.isCommunity()
        );
    }

    /** Follower / following list item: search result fields plus the caller's relation to the user. */
    public FollowUserDto toFollowItem(User user, boolean displayNameVisible, boolean isFollowing, boolean followsYou) {
        return new FollowUserDto(
                user.getId().toString(),
                user.getUsername(),
                displayNameVisible ? user.getDisplayName() : null,
                user.getAvatarUrl(),
                isFollowing,
                followsYou,
                user.isCommunity()
        );
    }

    /**
     * Convert User entity to profile response (including all fields); counts come from the profile projection
     */
    public MyProfileResponse toMyProfileResponse(User user, UserProfileProjection stats,
                                                  long createdPollCount, long votedPollCount,
                                                  Map<String, Boolean> visibilitySettings, boolean anonymousBallotByDefault) {
        return new MyProfileResponse(
                user.getId().toString(),
                user.getUsername(),
                user.getEmail(),
                user.getPhoneNumber(),
                user.isPhoneVerified(),
                user.getFirstName(),
                user.getLastName(),
                user.getBirthday(),
                user.getGender(),
                user.getDisplayName(),
                user.getBio(),
                user.getAvatarUrl(),
                user.getLocation(),
                user.isActive(),
                user.getLastLoginAt(),
                user.getCreatedAt(),
                stats.getTotalPolls().intValue(),
                stats.getTotalVotes().intValue(),
                (int) createdPollCount,
                (int) votedPollCount,
                stats.getFollowersCount().intValue(),
                stats.getFollowingCount().intValue(),
                visibilitySettings,
                anonymousBallotByDefault,
                user.isCommunity()
        );
    }

    /**
     * Convert Projection to public profile response, mask fields based on visibility settings
     */
    public UserProfileResponse toPublicProfileResponse(UserProfileProjection projection,
                                                        Map<String, Boolean> visibility,
                                                        Boolean isFollowing, Boolean isBlocked, Boolean followsYou,
                                                        Integer mutualFollowersCount, List<UserDto> mutualFollowersPreview) {
        return new UserProfileResponse(
                projection.getId().toString(),
                projection.getUsername(),
                visibility.getOrDefault("displayName", false) ? projection.getDisplayName() : null,
                visibility.getOrDefault("bio", false) ? projection.getBio() : null,
                projection.getAvatarUrl(),
                projection.getCreatedAt(),
                projection.getTotalPolls().intValue(),
                projection.getTotalVotes().intValue(),
                projection.getFollowersCount().intValue(),
                projection.getFollowingCount().intValue(),
                visibility.getOrDefault("firstName", false) ? projection.getFirstName() : null,
                visibility.getOrDefault("lastName", false) ? projection.getLastName() : null,
                visibility.getOrDefault("location", false) ? projection.getLocation() : null,
                visibility.getOrDefault("gender", false) ? projection.getGender() : null,
                isFollowing,
                followsYou,
                isBlocked,
                mutualFollowersCount,
                mutualFollowersPreview,
                projection.isCommunity()
        );
    }
}
