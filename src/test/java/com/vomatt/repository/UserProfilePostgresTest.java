package com.vomatt.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.vomatt.entity.User;
import com.vomatt.entity.UserPreference;

@DisplayName("User profile projection (Postgres)")
class UserProfilePostgresTest extends PostgresRepositoryTest {

    @Autowired UserRepository userRepository;
    @Autowired UserPreferenceRepository preferenceRepository;

    @Test
    @DisplayName("should count only Open and Ended Polls in totalPolls")
    void shouldCountOnlyOpenedPolls() {
        OffsetDateTime now = OffsetDateTime.now();
        User owner = persistUser("pp-" + System.nanoTime());
        owner.setAvatarUrl("bot:dog");
        persistPoll(owner, now.minusHours(1), now.plusDays(1), "A", "B");   // Open
        persistPoll(owner, now.minusDays(3), now.minusDays(1), "A", "B");   // Ended
        persistPoll(owner, now.plusHours(1), now.plusDays(1), "A", "B");    // Scheduled
        persistPoll(owner, now.minusHours(2), now.minusHours(3), "A", "B"); // Cancelled before opening
        em.flush();
        em.clear();

        UserProfileProjection profile = userRepository.findProfileByUsername(owner.getUsername()).orElseThrow();

        assertThat(profile.getTotalPolls()).isEqualTo(2L);
        assertThat(profile.getActive()).isTrue();
        assertThat(profile.getAvatarUrl()).isEqualTo("bot:dog");
    }

    @Test
    @DisplayName("should report active=false when user is suspended")
    void shouldReportSuspendedUser() {
        User owner = persistUser("ps-" + System.nanoTime());
        owner.setActive(false);
        em.flush();
        em.clear();

        assertThat(userRepository.findProfileByUsername(owner.getUsername()).orElseThrow().getActive()).isFalse();
    }

    @Test
    @DisplayName("should fetch one preference key for many users in one query")
    void shouldReturnOnlyRequestedKeyWhenFetchingPreferencesForManyUsers() {
        User a = persistUser("pa-" + System.nanoTime());
        User b = persistUser("pb-" + System.nanoTime());
        User c = persistUser("pc-" + System.nanoTime());
        for (User u : List.of(a, b)) {
            UserPreference pref = new UserPreference();
            pref.setUser(u);
            pref.setKey("visibility.displayName");
            pref.setValue(u == a ? "false" : "true");
            em.persist(pref);
        }
        UserPreference other = new UserPreference();
        other.setUser(c);
        other.setKey("visibility.bio");
        other.setValue("false");
        em.persist(other);
        em.flush();
        em.clear();

        assertThat(preferenceRepository.findByUserIdInAndKey(List.of(a.getId(), b.getId(), c.getId()), "visibility.displayName"))
                .extracting(p -> p.getUser().getId(), UserPreference::getValue)
                .containsExactlyInAnyOrder(tuple(a.getId(), "false"), tuple(b.getId(), "true"));
    }
}
