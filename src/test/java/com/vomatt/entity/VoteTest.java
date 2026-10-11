package com.vomatt.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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

    @Test
    @DisplayName("should end immediately when closing an Open Poll")
    void shouldEndOpenPollWhenClosed() {
        Vote vote = poll(now.minusHours(1), now.plusDays(1));

        vote.deactivate();

        assertThat(vote.getStatus()).isEqualTo(VoteStatus.ENDED);
        assertThat(vote.getEndTime()).isBefore(now.plusMinutes(1));
        assertThat(vote.isActive()).isFalse();
    }

    @Test
    @DisplayName("should cancel with end time before start time when closing a Scheduled Poll")
    void shouldEndScheduledPollWhenClosed() {
        OffsetDateTime start = now.plusDays(1);
        Vote vote = poll(start, now.plusDays(2));

        vote.deactivate();

        assertThat(vote.getStatus()).isEqualTo(VoteStatus.ENDED);
        assertThat(vote.getEndTime()).isBefore(start);
    }

    @Test
    @DisplayName("should not change end time when closing an already Ended Poll")
    void shouldNotChangeEndTimeWhenClosingEndedPoll() {
        OffsetDateTime end = now.minusDays(1);
        Vote vote = poll(now.minusDays(2), end);

        vote.deactivate();

        assertThat(vote.getEndTime()).isEqualTo(end);
        assertThat(vote.getStatus()).isEqualTo(VoteStatus.ENDED);
    }
}
