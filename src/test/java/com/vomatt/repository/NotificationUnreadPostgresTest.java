package com.vomatt.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import com.vomatt.entity.Audience;
import com.vomatt.entity.common.BaseEntity;
import com.vomatt.entity.NotificationKind;
import com.vomatt.entity.User;
import com.vomatt.entity.Vote;

/** The unread count and ids, bounded by mark-all-read inside each branch, agree with the feed's read flags. */
@Import(NotificationRepository.class)
class NotificationUnreadPostgresTest extends PostgresRepositoryTest {

    private static final Set<NotificationKind> ALL = EnumSet.allOf(NotificationKind.class);

    @Autowired NotificationRepository notificationRepository;

    private final OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
    private User me;
    private User other;

    @BeforeEach
    void setUp() {
        me = persistUser("unread-me-" + System.nanoTime());
        other = persistUser("unread-other-" + System.nanoTime());
    }

    private Vote endedPoll(OffsetDateTime revealAt) {
        Vote poll = persistPoll(me, now.minusDays(3), now.minusDays(1), "a", "b");
        addTurnout(poll, 1);
        em.createNativeQuery("UPDATE vomatt.votes SET reveal_at = :r WHERE id = :id")
                .setParameter("r", revealAt).setParameter("id", poll.getId()).executeUpdate();
        return poll;
    }

    private void follow(OffsetDateTime at) {
        User follower = persistUser("unread-f-" + System.nanoTime());
        em.flush();
        em.createNativeQuery("INSERT INTO vomatt.user_follows (id, follower_id, followed_id, created_at) VALUES (:id, :f, :me, :at)")
                .setParameter("id", BaseEntity.newTimeOrderedId()).setParameter("f", follower.getId())
                .setParameter("me", me.getId()).setParameter("at", at).executeUpdate();
    }

    private void like(Vote poll, OffsetDateTime at) {
        User liker = persistUser("unread-l-" + System.nanoTime());
        em.flush();
        em.createNativeQuery("INSERT INTO vomatt.vote_likes (id, user_id, vote_id, created_at) VALUES (:id, :u, :v, :at)")
                .setParameter("id", BaseEntity.newTimeOrderedId()).setParameter("u", liker.getId())
                .setParameter("v", poll.getId()).setParameter("at", at).executeUpdate();
    }

    // An Invite-only Poll of another user that I am invited to (invited a day ago)
    private Vote invitedPoll(OffsetDateTime endTime) {
        Vote poll = persistPoll(other, now.minusDays(2), endTime, Audience.INVITE_ONLY, "a", "b");
        em.flush();
        em.createNativeQuery("""
                INSERT INTO vomatt.vote_invitees (id, vote_id, user_id, created_at, updated_at) VALUES (:id, :v, :u, :at, :at)""")
                .setParameter("id", BaseEntity.newTimeOrderedId()).setParameter("v", poll.getId())
                .setParameter("u", me.getId()).setParameter("at", now.minusDays(1)).executeUpdate();
        return poll;
    }

    private Set<Map.Entry<NotificationKind, UUID>> unreadInFeed() {
        return notificationRepository.findPage(me.getId(), ALL, null, now, 50).stream().filter(n -> !n.read())
                .map(n -> Map.entry(n.kind(), n.sourceId())).collect(Collectors.toSet());
    }

    @Test
    void shouldCountOnlyNewerNotificationsWhenAllMarkedRead() {
        OffsetDateTime readAllAt = now.minusHours(2);
        Vote oldResult = endedPoll(null);                    // RESULT at its end, a day ago
        Vote lateReveal = endedPoll(now.minusHours(1));      // RESULT at its reveal, after mark-all-read
        endedPoll(now.plusHours(1));                         // still Sealed: no RESULT yet
        follow(now.minusDays(1));
        follow(now.minusHours(1));
        like(oldResult, now.minusDays(1));
        like(oldResult, now.minusHours(1));
        Vote closingEarly = invitedPoll(now.plusHours(3));   // CLOSING_SOON since 21 hours ago
        Vote closingLate = invitedPoll(now.plusHours(23));   // CLOSING_SOON since an hour ago
        invitedPoll(now.plusDays(3));                        // outside the window: no CLOSING_SOON
        em.flush();

        assertThat(notificationRepository.countUnread(me.getId(), ALL, now)).isEqualTo(11).isEqualTo(unreadInFeed().size());
        notificationRepository.markAllRead(me.getId(), readAllAt);

        Set<Map.Entry<NotificationKind, UUID>> unread = unreadInFeed();
        assertThat(unread).hasSize(4)
                .contains(Map.entry(NotificationKind.RESULT, lateReveal.getId()),
                        Map.entry(NotificationKind.CLOSING_SOON, closingLate.getId()))
                .extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder(NotificationKind.RESULT, NotificationKind.FOLLOWED, NotificationKind.POLL_LIKE,
                        NotificationKind.CLOSING_SOON);
        assertThat(notificationRepository.countUnread(me.getId(), ALL, now)).isEqualTo(4);
        assertThat(notificationRepository.findUnread(me.getId(), ALL, now)).containsExactlyInAnyOrderElementsOf(unread);
        assertThat(notificationRepository.findPage(me.getId(), EnumSet.of(NotificationKind.CLOSING_SOON), null, now, 50))
                .extracting(NotificationRepository.NotificationRow::sourceId)
                .containsExactlyInAnyOrder(closingEarly.getId(), closingLate.getId());
    }
}
