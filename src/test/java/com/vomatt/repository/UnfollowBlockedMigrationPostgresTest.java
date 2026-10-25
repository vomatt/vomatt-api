package com.vomatt.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import com.vomatt.entity.User;
import com.vomatt.entity.UserBlock;
import com.vomatt.entity.UserFollow;

/** Runs the real db/migrations/2026-10-25-unfollow-blocked.sql inside the rolled-back test transaction. */
@DisplayName("2026-10-25-unfollow-blocked.sql (Postgres)")
class UnfollowBlockedMigrationPostgresTest extends PostgresRepositoryTest {

    private static final String SCRIPT = "db/migrations/2026-10-25-unfollow-blocked.sql";

    @Test
    @DisplayName("should remove only follows of users the follower blocked and stay a no-op when run again")
    void shouldDeleteFollowWhenFollowerBlockedFollowed() throws Exception {
        User a = persistUser("ub-a-" + UUID.randomUUID());
        User b = persistUser("ub-b-" + UUID.randomUUID());
        User c = persistUser("ub-c-" + UUID.randomUUID());
        UserFollow blockedFollow = new UserFollow(a, b);
        UserFollow kept = new UserFollow(a, c);
        UserFollow reverse = new UserFollow(b, a);   // b did not block a, so b keeps following a
        em.persist(blockedFollow);
        em.persist(kept);
        em.persist(reverse);
        em.persist(new UserBlock(a, b));
        em.flush();

        String sql = new ClassPathResource(SCRIPT).getContentAsString(StandardCharsets.UTF_8).lines()
                .filter(line -> !line.startsWith("--")).collect(Collectors.joining("\n")).strip();
        for (int run = 0; run < 2; run++) {
            em.createNativeQuery(sql).executeUpdate();
        }
        em.clear();

        assertThat(em.find(UserFollow.class, blockedFollow.getId())).isNull();
        assertThat(em.find(UserFollow.class, kept.getId())).isNotNull();
        assertThat(em.find(UserFollow.class, reverse.getId())).isNotNull();
    }
}
