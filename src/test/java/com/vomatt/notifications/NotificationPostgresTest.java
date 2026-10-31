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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.security.RefreshTokenService;
import com.vomatt.entity.Audience;
import com.vomatt.entity.Invitee;
import com.vomatt.entity.User;
import com.vomatt.entity.UserVote;
import com.vomatt.entity.Vote;
import com.vomatt.repository.NotificationRepository;
import com.vomatt.repository.PostgresRepositoryTest;
import com.vomatt.users.UserMapper;
import com.vomatt.users.UserService;
import com.vomatt.votes.VoteService;
import com.vomatt.votes.VoteServiceSlice;
import com.vomatt.votes.dto.InviteesRequest;
import com.vomatt.votes.dto.VoteRequest;
import com.vomatt.votes.dto.VoteResponse;

/** Invitations and Ended Notifications derived on read (ADR 0003). */
@Import({ NotificationService.class, VoteServiceSlice.class, UserService.class, UserMapper.class })
@DisplayName("Notifications (Postgres)")
class NotificationPostgresTest extends PostgresRepositoryTest {

    @MockitoBean RefreshTokenService refreshTokenService;
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
        em.persist(new UserVote(user, poll, poll.getOptions().iterator().next()));
        addTurnout(poll, 1);
    }

    private Vote invitePoll(OffsetDateTime start, OffsetDateTime end, User... invitees) {
        Vote poll = poll(start, end);
        poll.setAudience(Audience.INVITE_ONLY);
        for (User invitee : invitees) {
            em.persist(new Invitee(poll, invitee));
        }
        return poll;
    }

    private void markRead(User user, Vote poll) {
        em.flush();
        notificationService.markRead(user.getId().toString(), poll.getId().toString());
    }

    private Boolean myPollUnread(User user, String status, Vote poll) {
        em.flush();
        return voteService.getMyPolls(user.getId().toString(), status, null, 50).items().stream()
                .filter(v -> v.getId().equals(poll.getId().toString())).findFirst().orElseThrow().getUnread();
    }

    private long unread(User user) {
        em.flush();
        return notificationService.countUnread(user.getId().toString(), null).count();
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
    @DisplayName("should stop counting at the cap when more notifications are unread")
    void shouldCapUnreadCountWhenMoreThanCapUnread() {
        for (int i = 0; i <= NotificationRepository.UNREAD_COUNT_CAP; i++) {
            ballot(participant, poll(now.minusDays(2), now.minusHours(1)));
        }

        assertThat(unread(participant)).isEqualTo(NotificationRepository.UNREAD_COUNT_CAP);
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
        scheduled.setEndTime(now.minusMinutes(1)); // Cancelled before Close was limited: ends before its start time

        assertThat(unread(owner)).isZero();
    }

    @Test
    @DisplayName("should show the notification immediately when a Poll is closed early")
    void shouldNotifyImmediatelyWhenClosedEarly() {
        Vote open = poll(now.minusHours(1), now.plusDays(3));
        ballot(participant, open);
        assertThat(unread(participant)).isZero();

        open.setAudience(Audience.UNLISTED);
        open.closeAt(OffsetDateTime.now());

        assertThat(unread(participant)).isEqualTo(1);
    }

    @Test
    @DisplayName("should mark as read idempotently and update the unread count")
    void shouldMarkNotificationReadIdempotently() {
        Vote ended = poll(now.minusDays(2), now.minusHours(1));
        ballot(participant, ended);
        ballot(participant, poll(now.minusDays(3), now.minusHours(2)));
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
        ballot(participant, unreadPoll);
        ballot(participant, readPoll);
        Vote cancelled = poll(now.plusDays(1), now.plusDays(2));
        cancelled.setEndTime(now.minusMinutes(1)); // Cancelled before Close was limited
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

    @Test
    @DisplayName("should notify Invitees of Scheduled and Open Invite-only Polls, not the owner or Public Participants")
    void shouldNotifyInviteeWhenInvitedToInviteOnlyPoll() {
        Vote open = invitePoll(now.minusHours(1), now.plusDays(1), participant);
        invitePoll(now.plusDays(1), now.plusDays(2), participant);
        ballot(bystander, poll(now.minusHours(1), now.plusDays(1)));  // Open Public Poll: no Invitation

        assertThat(unread(participant)).isEqualTo(2);
        assertThat(unread(owner)).isZero();
        assertThat(unread(bystander)).isZero();
        assertThat(myPollUnread(participant, "open", open)).isTrue();
        assertThat(myPollUnread(owner, "open", open)).isFalse();
    }

    @Test
    @DisplayName("should keep the Ended Notification unread when the Invitation was read before the Poll Ended")
    void shouldNotifyEndedWhenInvitationReadBeforePollEnded() {
        Vote poll = invitePoll(now.minusHours(1), now.plusDays(1), participant);
        addTurnout(poll, 1);
        markRead(participant, poll);
        markRead(participant, poll);
        assertThat(unread(participant)).isZero();
        assertThat(myPollUnread(participant, "open", poll)).isFalse();

        poll.closeAt(OffsetDateTime.now());

        assertThat(unread(participant)).isEqualTo(1);
        assertThat(myPollUnread(participant, "ended", poll)).isTrue();
        markRead(participant, poll);
        assertThat(unread(participant)).isZero();
        assertThat(myPollUnread(participant, "ended", poll)).isFalse();
    }

    @Test
    @DisplayName("should send the Ended Notification to every Invitee, Ballot or not, and only to Participants otherwise")
    void shouldNotifyEveryInviteeWhenInviteOnlyPollEnded() {
        User retracted = persistUser("n-ret-" + System.nanoTime());
        Vote inviteOnly = invitePoll(now.minusDays(2), now.minusHours(1), participant, bystander);
        ballot(participant, inviteOnly);  // bystander never cast a Ballot
        Vote unlisted = poll(now.minusDays(2), now.minusHours(1));
        unlisted.setAudience(Audience.UNLISTED);
        ballot(participant, unlisted);  // retracted made a Retraction here: no Ballot row left

        assertThat(unread(participant)).isEqualTo(2);
        assertThat(unread(bystander)).isEqualTo(1);
        assertThat(unread(retracted)).isZero();
        assertThat(unread(owner)).isEqualTo(2);
    }

    @Test
    @DisplayName("should send the Ended Notification to every Invitee when the last Invitee's Ballot auto-ends the Poll")
    void shouldNotifyInviteesWhenPollAutoEnded() {
        Vote poll = invitePoll(now.minusHours(1), now.plusDays(1), participant, bystander);
        em.flush();
        String voteId = poll.getId().toString();
        for (User invitee : List.of(participant, bystander)) {
            String userId = invitee.getId().toString();
            markRead(invitee, poll); // the Invitation, so only the Ended Notification can be unread
            VoteRequest ballot = new VoteRequest();
            ballot.setOptionIds(List.of(voteService.getVote(voteId, userId).getOptions().getFirst().getId()));
            voteService.vote(voteId, ballot, userId);
        }

        assertThat(unread(participant)).isEqualTo(1);
        assertThat(unread(bystander)).isEqualTo(1);
        assertThat(myPollUnread(participant, "ended", poll)).isTrue();
    }

    @Test
    @DisplayName("should send the Ended Notification to an Invitee who Retracted")
    void shouldNotifyInviteeWhenRetractedBeforePollEnded() {
        // participant Retracts before bystander casts a Ballot, so the Poll does not auto-End and its Turnout stays 1
        Vote poll = invitePoll(now.minusHours(1), now.plusDays(1), participant, bystander);
        em.flush();
        String voteId = poll.getId().toString();
        String userId = participant.getId().toString();
        VoteRequest ballot = new VoteRequest();
        ballot.setOptionIds(List.of(voteService.getVote(voteId, userId).getOptions().getFirst().getId()));
        voteService.vote(voteId, ballot, userId);
        voteService.retract(voteId, userId);
        voteService.vote(voteId, ballot, bystander.getId().toString());

        voteService.closeVote(voteId, owner.getId().toString());

        assertThat(unread(participant)).isEqualTo(1);
        assertThat(myPollUnread(participant, "ended", poll)).isTrue();
    }

    @Test
    @DisplayName("should give no Invitation or Ended Notification for a Deleted Poll")
    void shouldNotNotifyWhenPollDeleted() {
        Vote open = invitePoll(now.minusHours(1), now.plusDays(1), participant);
        Vote ended = invitePoll(now.minusDays(2), now.minusHours(1), participant);
        addTurnout(ended, 1);
        assertThat(unread(participant)).isEqualTo(2);

        open.setDeletedAt(now);
        ended.setDeletedAt(now);

        assertThat(unread(participant)).isZero();
        assertThat(unread(owner)).isZero();
        assertThatThrownBy(() -> markRead(participant, open))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.VOTE_NOT_FOUND));
    }

    @Test
    @DisplayName("should drop the Invitation when the Invitee is removed")
    void shouldNotNotifyWhenInviteeRemoved() {
        Vote poll = invitePoll(now.minusHours(1), now.plusDays(1), participant);
        assertThat(unread(participant)).isEqualTo(1);

        voteService.removeInvitee(poll.getId().toString(), participant.getId().toString(), owner.getId().toString());

        assertThat(unread(participant)).isZero();
    }

    @Test
    @DisplayName("should show the Invitation unread again when a removed Invitee who read it is invited again")
    void shouldNotifyAgainWhenInviteeReAdded() {
        Vote poll = invitePoll(now.minusHours(1), now.plusDays(1), participant);
        markRead(participant, poll);
        voteService.removeInvitee(poll.getId().toString(), participant.getId().toString(), owner.getId().toString());

        InviteesRequest request = new InviteesRequest();
        request.setUserIds(List.of(participant.getId()));
        voteService.addInvitees(poll.getId().toString(), request, owner.getId().toString());

        assertThat(unread(participant)).isEqualTo(1);
        assertThat(myPollUnread(participant, "open", poll)).isTrue();
    }
}
