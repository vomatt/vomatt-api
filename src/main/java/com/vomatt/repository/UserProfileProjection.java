package com.vomatt.repository;

import java.time.OffsetDateTime;
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
    Boolean getActive();
    // Extended fields whose visibility can be controlled
    String getFirstName();
    String getLastName();
    String getLocation();
    Integer getPoints();
    String getMembershipLevel();
}
