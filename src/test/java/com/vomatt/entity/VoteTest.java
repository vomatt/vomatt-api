package com.vomatt.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;

@DisplayName("Vote (Poll lifecycle)")
class VoteTest {

    private final OffsetDateTime now = OffsetDateTime.now();

    private Vote poll(OffsetDateTime start, OffsetDateTime end) {
        Vote vote = new Vote();
        vote.setStartTime(start);
        vote.setEndTime(end);
        return vote;
    }

    @Test
    @DisplayName("should change Audience when Poll is Scheduled")
    void shouldChangeAudienceWhenScheduled() {
        Vote vote = poll(now.plusHours(1), now.plusDays(1));

        vote.changeAudience(Audience.UNLISTED, now);

        assertThat(vote.getAudience()).isEqualTo(Audience.UNLISTED);
    }

    @Test
    @DisplayName("should refuse an Audience change with vote.not_editable when Poll is Open or Ended")
    void shouldRefuseAudienceChangeWhenOpenOrEnded() {
        for (Vote vote : new Vote[] { poll(now.minusHours(1), now.plusDays(1)), poll(now.minusDays(2), now.minusDays(1)) }) {
            assertThatThrownBy(() -> vote.changeAudience(Audience.UNLISTED, now))
                    .isInstanceOfSatisfying(ApiException.class,
                            ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.VOTE_NOT_EDITABLE));
            assertThat(vote.getAudience()).isEqualTo(Audience.PUBLIC);
        }
    }

    @Test
    @DisplayName("should be Scheduled when start time has not arrived")
    void shouldBeScheduledWhenStartTimeInFuture() {
        assertThat(poll(now.plusHours(1), now.plusDays(1)).getStatus()).isEqualTo(VoteStatus.SCHEDULED);
    }

    @Test
    @DisplayName("should be Open when after start and before end")
    void shouldBeOpenBetweenStartAndEnd() {
        Vote vote = poll(now.minusHours(1), now.plusDays(1));
        assertThat(vote.getStatus()).isEqualTo(VoteStatus.OPEN);
        assertThat(vote.isVotingActive()).isTrue();
    }

    @Test
    @DisplayName("should be Ended when end time has passed")
    void shouldBeEndedWhenEndTimePassed() {
        Vote vote = poll(now.minusDays(2), now.minusDays(1));
        assertThat(vote.getStatus()).isEqualTo(VoteStatus.ENDED);
        assertThat(vote.isVotingActive()).isFalse();
    }

    @Test
    @DisplayName("should prefer Ended over Scheduled when end time is before start time")
    void shouldPreferEndedOverScheduled() {
        assertThat(poll(now.plusDays(1), now.minusMinutes(1)).getStatus()).isEqualTo(VoteStatus.ENDED);
    }

    // ─── Close ──────────────────────────────────────────────────────────────

    private Vote poll(OffsetDateTime start, OffsetDateTime deadline, Audience audience) {
        Vote vote = new Vote("Poll", null, null, deadline);
        vote.setStartTime(start);
        vote.setAudience(audience);
        return vote;
    }

    @Test
    @DisplayName("should end now and keep the Deadline when closing an Open Unlisted or Invite-only Poll")
    void shouldEndAndKeepDeadlineWhenClosingOpenNonPublicPoll() {
        OffsetDateTime deadline = now.plusDays(1);
        for (Audience audience : new Audience[] { Audience.UNLISTED, Audience.INVITE_ONLY }) {
            Vote vote = poll(now.minusHours(1), deadline, audience);

            vote.closeAt(now);

            assertThat(vote.statusAt(now)).isEqualTo(VoteStatus.ENDED);
            assertThat(vote.getEndTime()).isEqualTo(now);
            assertThat(vote.getDeadline()).isEqualTo(deadline);
            assertThat(vote.isActive()).isFalse();
        }
    }

    // ─── Auto-end ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("should end now and keep the Deadline when every Invitee of an Open Invite-only Poll holds a Ballot")
    void shouldAutoEndAndKeepDeadlineWhenAllInviteesVoted() {
        OffsetDateTime deadline = now.plusDays(1);
        Vote vote = poll(now.minusHours(1), deadline, Audience.INVITE_ONLY);

        assertThat(vote.endIfAllInviteesVotedAt(now, () -> true)).isTrue();

        assertThat(vote.statusAt(now)).isEqualTo(VoteStatus.ENDED);
        assertThat(vote.getEndTime()).isEqualTo(now);
        assertThat(vote.getDeadline()).isEqualTo(deadline);
        assertThat(vote.isActive()).isFalse();
    }

    @Test
    @DisplayName("should stay Open when some Invitee holds no Ballot")
    void shouldNotAutoEndWhenAnInviteeHasNoBallot() {
        OffsetDateTime deadline = now.plusDays(1);
        Vote vote = poll(now.minusHours(1), deadline, Audience.INVITE_ONLY);

        assertThat(vote.endIfAllInviteesVotedAt(now, () -> false)).isFalse();

        assertThat(vote.getEndTime()).isEqualTo(deadline);
        assertThat(vote.isActive()).isTrue();
    }

    @Test
    @DisplayName("should never auto-end without asking when the Poll is not Invite-only or not Open")
    void shouldNotAutoEndWhenNotInviteOnlyOrNotOpen() {
        Vote[] polls = {
            poll(now.minusHours(1), now.plusDays(1), Audience.PUBLIC),
            poll(now.minusHours(1), now.plusDays(1), Audience.UNLISTED),
            poll(now.plusHours(1), now.plusDays(1), Audience.INVITE_ONLY),
            poll(now.minusDays(2), now.minusDays(1), Audience.INVITE_ONLY),
        };
        for (Vote vote : polls) {
            OffsetDateTime endTime = vote.getEndTime();

            assertThat(vote.endIfAllInviteesVotedAt(now, () -> { throw new AssertionError("asked"); })).isFalse();

            assertThat(vote.getEndTime()).isEqualTo(endTime);
        }
    }

    @Test
    @DisplayName("should never auto-end without asking when the Invite-only Poll is Deleted")
    void shouldNotAutoEndWhenDeleted() {
        OffsetDateTime deadline = now.plusDays(1);
        Vote vote = poll(now.minusHours(1), deadline, Audience.INVITE_ONLY);
        vote.setDeletedAt(now);

        assertThat(vote.endIfAllInviteesVotedAt(now, () -> { throw new AssertionError("asked"); })).isFalse();

        assertThat(vote.getEndTime()).isEqualTo(deadline);
    }

    @Test
    @DisplayName("should refuse with vote.close.public_not_allowed when closing an Open Public Poll")
    void shouldRefuseCloseWhenPublic() {
        OffsetDateTime deadline = now.plusDays(1);
        Vote vote = poll(now.minusHours(1), deadline, Audience.PUBLIC);

        assertThatThrownBy(() -> vote.closeAt(now))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.VOTE_CLOSE_PUBLIC_NOT_ALLOWED));
        assertThat(vote.getEndTime()).isEqualTo(deadline);
        assertThat(vote.isActive()).isTrue();
    }

    @Test
    @DisplayName("should refuse with vote.close.scheduled when closing a Scheduled Poll of any Audience")
    void shouldRefuseCloseWhenScheduled() {
        OffsetDateTime deadline = now.plusDays(2);
        for (Audience audience : Audience.values()) {
            Vote vote = poll(now.plusDays(1), deadline, audience);

            assertThatThrownBy(() -> vote.closeAt(now))
                    .isInstanceOfSatisfying(ApiException.class,
                            ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.VOTE_CLOSE_SCHEDULED));
            assertThat(vote.getEndTime()).isEqualTo(deadline);
        }
    }

    @Test
    @DisplayName("should not change end time when closing an already Ended Poll, Public included")
    void shouldNotChangeEndTimeWhenClosingEndedPoll() {
        OffsetDateTime deadline = now.minusDays(1);
        for (Audience audience : Audience.values()) {
            Vote vote = poll(now.minusDays(2), deadline, audience);

            vote.closeAt(now);

            assertThat(vote.getEndTime()).isEqualTo(deadline);
            assertThat(vote.statusAt(now)).isEqualTo(VoteStatus.ENDED);
        }
    }

    // ─── Delete window ───────────────────────────────────────────────────────

    private static final Duration WINDOW = Duration.ofMinutes(20);

    private Vote posted(OffsetDateTime createdAt, OffsetDateTime start, OffsetDateTime end) {
        Vote vote = poll(start, end);
        ReflectionTestUtils.setField(vote, "createdAt", createdAt);
        return vote;
    }

    @Test
    @DisplayName("should allow Delete when an Open Poll is just inside the window")
    void shouldAllowDeleteWhenJustInsideWindow() {
        OffsetDateTime createdAt = now.minus(WINDOW).plusSeconds(1);
        assertThat(posted(createdAt, createdAt, now.plusDays(1)).canDeleteAt(now, WINDOW)).isTrue();
    }

    @Test
    @DisplayName("should refuse Delete when an Open Poll reaches the end of the window")
    void shouldRefuseDeleteWhenWindowReached() {
        OffsetDateTime createdAt = now.minus(WINDOW);
        assertThat(posted(createdAt, createdAt, now.plusDays(1)).canDeleteAt(now, WINDOW)).isFalse();
    }

    @Test
    @DisplayName("should allow Delete even with Ballots when within the window and already Ended")
    void shouldAllowDeleteWhenEndedWithinWindow() {
        OffsetDateTime createdAt = now.minusMinutes(5);
        assertThat(posted(createdAt, createdAt, now.minusMinutes(1)).canDeleteAt(now, WINDOW)).isTrue();
    }

    @Test
    @DisplayName("should allow Delete when Scheduled long after posting")
    void shouldAllowDeleteWhenScheduled() {
        Vote vote = posted(now.minusDays(3), now.plusHours(1), now.plusDays(1));
        assertThat(vote.canDeleteAt(now, WINDOW)).isTrue();
    }

    @Test
    @DisplayName("should refuse Delete when already Deleted")
    void shouldRefuseDeleteWhenAlreadyDeleted() {
        Vote vote = posted(now.minusMinutes(1), now.plusHours(1), now.plusDays(1));
        vote.setDeletedAt(now);
        assertThat(vote.canDeleteAt(now, WINDOW)).isFalse();
    }

    // ─── Audience access ─────────────────────────────────────────────────────

    private Vote ownedBy(UUID ownerId, Audience audience) {
        Vote vote = poll(now.minusHours(1), now.plusDays(1));
        vote.setCreator(User.builder().id(ownerId).build());
        vote.setAudience(audience);
        return vote;
    }

    @Test
    @DisplayName("should show an Invite-only Poll only to its owner and Invitees when looked up")
    void shouldShowInviteOnlyPollOnlyToOwnerAndInviteesWhenLookedUp() {
        UUID ownerId = UUID.randomUUID();
        Vote vote = ownedBy(ownerId, Audience.INVITE_ONLY);
        String stranger = UUID.randomUUID().toString();

        assertThat(vote.isVisibleTo(ownerId.toString(), () -> { throw new AssertionError("owner needs no Invitee lookup"); })).isTrue();
        assertThat(vote.isVisibleTo(stranger, () -> true)).isTrue();
        assertThat(vote.isVisibleTo(stranger, () -> false)).isFalse();
        assertThat(vote.isVisibleTo(null, () -> true)).isFalse();
    }

    @Test
    @DisplayName("should show Public and Unlisted Polls to anyone, and a Deleted Poll to nobody, when looked up")
    void shouldShowByAudienceAndDeletionWhenLookedUp() {
        UUID ownerId = UUID.randomUUID();
        assertThat(ownedBy(ownerId, Audience.PUBLIC).isVisibleTo(null, () -> false)).isTrue();
        assertThat(ownedBy(ownerId, Audience.UNLISTED).isVisibleTo(null, () -> false)).isTrue();

        Vote deleted = ownedBy(ownerId, Audience.INVITE_ONLY);
        deleted.setDeletedAt(now);
        assertThat(deleted.isVisibleTo(ownerId.toString(), () -> true)).isFalse();
    }

    @Test
    @DisplayName("should allow Invitee changes when an Invite-only Poll is Scheduled or Open")
    void shouldAllowInviteeChangesWhenScheduledOrOpen() {
        for (Vote vote : new Vote[] { poll(now.plusHours(1), now.plusDays(1)), poll(now.minusHours(1), now.plusDays(1)) }) {
            vote.setAudience(Audience.INVITE_ONLY);
            vote.checkInviteesEditable(now);
        }
    }

    @Test
    @DisplayName("should refuse Invitee changes when the Poll is Ended or not Invite-only")
    void shouldRefuseInviteeChangesWhenEndedOrNotInviteOnly() {
        Vote ended = poll(now.minusDays(2), now.minusDays(1));
        ended.setAudience(Audience.INVITE_ONLY);
        assertThatThrownBy(() -> ended.checkInviteesEditable(now))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.VOTE_ENDED));

        Vote unlisted = poll(now.minusHours(1), now.plusDays(1));
        unlisted.setAudience(Audience.UNLISTED);
        assertThatThrownBy(() -> unlisted.checkInviteesEditable(now))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.VOTE_INVITEE_NOT_INVITE_ONLY));
    }

    @Test
    void shouldParseLowerCaseStatusWhenKnown() {
        assertThat(VoteStatus.parse("scheduled")).isEqualTo(VoteStatus.SCHEDULED);
        assertThat(VoteStatus.parse("ended")).isEqualTo(VoteStatus.ENDED);
        assertThatThrownBy(() -> VoteStatus.parse("OPEN")).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getMessageKey()).isEqualTo(MessageKey.COMMON_INVALID_STATUS));
    }
}
