package com.vomatt.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.entity.User;
import com.vomatt.entity.UserVote;
import com.vomatt.entity.Vote;
import com.vomatt.repository.PostgresRepositoryTest;
import com.vomatt.votes.VoteService;
import com.vomatt.votes.VoteServiceSlice;
import com.vomatt.votes.dto.VoteResponse;

/** Ended Notifications derived on read (ADR 0003). */
@Import({ NotificationService.class, VoteServiceSlice.class })
@DisplayName("Ended Notifications (Postgres)")
class NotificationPostgresTest extends PostgresRepositoryTest {

    @Autowired NotificationService notificationService;
    @Autowired VoteService voteService;

    private final OffsetDateTime now = OffsetDateTime.now();
    private User owner;
    private User participant;
    private User bystander;

    @BeforeEach
    void setUp() {
        owner = persistUser("n-owner-" + System.nanoTime());
        participant = persistUser("n-part-" + System.nanoTime());
        bystander = persistUser("n-by-" + System.nanoTime());
    }

    private Vote poll(OffsetDateTime start, OffsetDateTime end) {
        return persistPoll(owner, start, end, "A", "B");
    }

    private void ballot(User user, Vote poll) {
        em.persist(new UserVote(user, poll, poll.getOptions().iterator().next(), null));
    }

    private long unread(User user) {
        em.flush();
        return notificationService.countUnread(user.getId().toString()).count();
    }

    @Test
    @DisplayName("should notify the creator and Participants but not bystanders when a Poll ends")
    void shouldNotifyOwnerAndParticipantsWhenPollEnded() {
        ballot(participant, poll(now.minusDays(2), now.minusHours(1)));
        poll(now.minusHours(1), now.plusDays(1));  // still Open: no notification yet

        assertThat(unread(owner)).isEqualTo(1);
        assertThat(unread(participant)).isEqualTo(1);
        assertThat(unread(bystander)).isZero();
    }

    @Test
    @DisplayName("should not notify users who withdrew (no longer hold a Ballot)")
    void shouldNotNotifyRetractedUser() {
        poll(now.minusDays(2), now.minusHours(1));  // participant retracted: no Ballot row left

        assertThat(unread(participant)).isZero();
    }

    @Test
    @DisplayName("should not create notifications for a cancelled Scheduled Poll")
    void shouldNotNotifyWhenScheduledPollCancelled() {
        Vote scheduled = poll(now.plusDays(1), now.plusDays(2));
        scheduled.deactivate();

        assertThat(unread(owner)).isZero();
    }

    @Test
    @DisplayName("should show the notification immediately when a Poll is closed early")
    void shouldNotifyImmediatelyWhenClosedEarly() {
        Vote open = poll(now.minusHours(1), now.plusDays(3));
        ballot(participant, open);
        assertThat(unread(participant)).isZero();

        open.deactivate();

        assertThat(unread(participant)).isEqualTo(1);
    }

    @Test
    @DisplayName("should mark as read idempotently and update the unread count")
    void shouldMarkNotificationReadIdempotently() {
        Vote ended = poll(now.minusDays(2), now.minusHours(1));
        poll(now.minusDays(3), now.minusHours(2));
        em.flush();
        assertThat(unread(owner)).isEqualTo(2);

        notificationService.markRead(owner.getId().toString(), ended.getId().toString());
        notificationService.markRead(owner.getId().toString(), ended.getId().toString());

        assertThat(unread(owner)).isEqualTo(1);
    }

    @Test
    @DisplayName("should return 404 when the user does not have the notification")
    void shouldRejectMarkingPollWithoutNotification() {
        Vote ended = poll(now.minusDays(2), now.minusHours(1));
        em.flush();

        assertThatThrownBy(() -> notificationService.markRead(bystander.getId().toString(), ended.getId().toString()))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.NOTIFICATION_NOT_FOUND));
    }

    @Test
    @DisplayName("should flag unread on the My Polls ended tab, false for read and cancelled Polls")
    void shouldFlagUnreadInEndedMyPolls() {
        Vote unreadPoll = poll(now.minusDays(2), now.minusHours(1));
        Vote readPoll = poll(now.minusDays(3), now.minusHours(2));
        Vote cancelled = poll(now.plusDays(1), now.plusDays(2));
        cancelled.deactivate();
        em.flush();
        notificationService.markRead(owner.getId().toString(), readPoll.getId().toString());
        em.flush();
        em.clear();

        List<VoteResponse> ended = voteService.getMyPolls(owner.getId().toString(), "ended", null, null).items();

        assertThat(ended).extracting(VoteResponse::getId, VoteResponse::getUnread).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple(unreadPoll.getId().toString(), true),
                org.assertj.core.groups.Tuple.tuple(readPoll.getId().toString(), false),
                org.assertj.core.groups.Tuple.tuple(cancelled.getId().toString(), false));
    }
}
