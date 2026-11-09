package com.vomatt.repository;

import com.vomatt.common.constant.UserRole;
import com.vomatt.entity.Gender;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.UUID;

/**
 * Projection interface: fetch User basic info and stats in one query, avoiding multiple queries
 */
public interface UserProfileProjection {
    UUID getId();
    String getUsername();
    String getDisplayName();
    String getBio();
    String getAvatarUrl();
    OffsetDateTime getCreatedAt();
    Long getTotalPolls();
    Long getTotalVotes();
    Long getFollowersCount();
    Long getFollowingCount();
    Boolean getActive();
    String[] getRoles();

    /** Same rule as {@link com.vomatt.entity.User#isOfficial()}. */
    default boolean isOfficial() {
        return getRoles() != null && Arrays.asList(getRoles()).contains(UserRole.OFFICIAL);
    }
    // Extended fields whose visibility can be controlled
    String getFirstName();
    String getLastName();
    String getLocation();
    Gender getGender();
    // Viewer relation; false when there is no viewer
    Boolean getFollowing();
    Boolean getBlocked();
    /** Whether the user follows the viewer */
    Boolean getFollowsViewer();
}
