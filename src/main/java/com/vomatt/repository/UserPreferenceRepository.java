package com.vomatt.repository;

import com.vomatt.entity.UserPreference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserPreferenceRepository extends JpaRepository<UserPreference, UUID> {

    /**
     * Batch-fetch preferences with the given prefix (e.g. visibility.*)
     */
    List<UserPreference> findByUserIdAndKeyStartingWith(UUID userId, String keyPrefix);

    /**
     * Fetch a single preference
     */
    Optional<UserPreference> findByUserIdAndKey(UUID userId, String key);

    /**
     * Fetch one preference for many users at once
     */
    List<UserPreference> findByUserIdInAndKey(Collection<UUID> userIds, String key);
}
