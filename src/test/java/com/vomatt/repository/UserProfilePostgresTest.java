package com.vomatt.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.vomatt.entity.User;

@DisplayName("User profile projection (Postgres)")
class UserProfilePostgresTest extends PostgresRepositoryTest {

    @Autowired UserRepository userRepository;

    @Test
    @DisplayName("應該只把 Open 與 Ended 的 Poll 算進 totalPolls")
    void shouldCountOnlyOpenedPolls() {
        OffsetDateTime now = OffsetDateTime.now();
        User owner = persistUser("pp-" + System.nanoTime());
        persistPoll(owner, now.minusHours(1), now.plusDays(1), "A", "B");   // Open
        persistPoll(owner, now.minusDays(3), now.minusDays(1), "A", "B");   // Ended
        persistPoll(owner, now.plusHours(1), now.plusDays(1), "A", "B");    // Scheduled
        persistPoll(owner, now.minusHours(2), now.minusHours(3), "A", "B"); // Cancelled before opening
        em.flush();
        em.clear();

        UserProfileProjection profile = userRepository.findProfileByUsername(owner.getUsername()).orElseThrow();

        assertThat(profile.getTotalPolls()).isEqualTo(2L);
        assertThat(profile.getActive()).isTrue();
    }

    @Test
    @DisplayName("應該在使用者已停權時回報 active=false")
    void shouldReportSuspendedUser() {
        User owner = persistUser("ps-" + System.nanoTime());
        owner.setActive(false);
        em.flush();
        em.clear();

        assertThat(userRepository.findProfileByUsername(owner.getUsername()).orElseThrow().getActive()).isFalse();
    }
}
