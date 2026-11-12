package com.vomatt.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import com.vomatt.entity.NotificationKind;
import com.vomatt.entity.User;

/** COMMUNITY_LOCKED comes from the community account's own lock, with no audit log involved. */
@Import(NotificationRepository.class)
class CommunityLockedNotificationPostgresTest extends PostgresRepositoryTest {

    @Autowired NotificationRepository notificationRepository;
    @Autowired UserRepository userRepository;

    private User owner;

    private UUID ownedCommunity() {
        User community = persistUser("lockn-" + System.nanoTime());
        em.flush();
        em.createNativeQuery("INSERT INTO vomatt.community_members (community_user_id, user_id, role) VALUES (:c, :u, 'OWNER')")
                .setParameter("c", community.getId()).setParameter("u", owner.getId()).executeUpdate();
        return community.getId();
    }

    private List<NotificationRepository.NotificationRow> feed() {
        return notificationRepository.findPage(owner.getId(), EnumSet.of(NotificationKind.COMMUNITY_LOCKED), null,
                OffsetDateTime.now(), 50);
    }

    @Test
    void shouldListDeadlineLockFromLockedAtWhenNoAuditEntryExists() {
        owner = persistUser("lockn-owner-" + System.nanoTime());
        UUID deadline = ownedCommunity();
        UUID chosen = ownedCommunity();
        OffsetDateTime at = OffsetDateTime.now().minusMinutes(5).truncatedTo(ChronoUnit.MICROS);
        userRepository.markLocked(List.of(deadline), at, "SELECTION_DEADLINE");
        userRepository.markLocked(List.of(chosen), at, "USER_CHOICE");

        assertThat(feed()).singleElement().satisfies(n -> {
            assertThat(n.sourceId()).isEqualTo(deadline);
            assertThat(n.actorId()).isEqualTo(deadline);
            assertThat(n.createdAt().toInstant()).isEqualTo(at.toInstant());
            assertThat(n.read()).isFalse();
        });

        // Read, unlocked (gone), locked again: unread again
        notificationRepository.markRead(owner.getId(), NotificationKind.COMMUNITY_LOCKED, deadline, OffsetDateTime.now());
        assertThat(feed()).singleElement().extracting(NotificationRepository.NotificationRow::read).isEqualTo(true);
        userRepository.markUnlocked(List.of(deadline));
        assertThat(feed()).isEmpty();
        userRepository.markLocked(List.of(deadline), OffsetDateTime.now(), "SELECTION_DEADLINE");
        assertThat(feed()).singleElement().extracting(NotificationRepository.NotificationRow::read).isEqualTo(false);
    }
}
