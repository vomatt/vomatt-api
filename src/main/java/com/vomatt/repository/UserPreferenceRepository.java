package com.vomatt.repository;

import com.vomatt.entity.UserPreference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    List<UserPreference> findByUserId(UUID userId);

    /**
     * Fetch a single preference
     */
    Optional<UserPreference> findByUserIdAndKey(UUID userId, String key);

    /** The account default for whether new Ballots are Anonymous; false when never set. */
    default boolean anonymousBallotByDefault(UUID userId) {
        return findByUserIdAndKey(userId, UserPreference.ANONYMOUS_BALLOT_KEY)
            .map(pref -> Boolean.parseBoolean(pref.getValue()))
            .orElse(false);
    }

    /**
     * Fetch one preference for many users at once
     */
    List<UserPreference> findByUserIdInAndKey(Collection<UUID> userIds, String key);

    /** Sets one preference; concurrent writes of the same key keep the last value instead of failing on the unique key. */
    @Modifying
    @Query(value = """
            INSERT INTO vomatt.user_preferences (id, user_id, preference_key, preference_value) VALUES (:id, :userId, :key, :value)
            ON CONFLICT (user_id, preference_key) DO UPDATE SET preference_value = EXCLUDED.preference_value, updated_at = now()""",
            nativeQuery = true)
    void upsert(@Param("id") UUID id, @Param("userId") UUID userId, @Param("key") String key, @Param("value") String value);
}
