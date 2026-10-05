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
    @DisplayName("應該在開始時間未到時為 Scheduled")
    void shouldBeScheduledWhenStartTimeInFuture() {
        assertThat(poll(now.plusHours(1), now.plusDays(1)).getStatus()).isEqualTo(VoteStatus.SCHEDULED);
    }

    @Test
    @DisplayName("應該在開始後、結束前為 Open")
    void shouldBeOpenBetweenStartAndEnd() {
        Vote vote = poll(now.minusHours(1), now.plusDays(1));
        assertThat(vote.getStatus()).isEqualTo(VoteStatus.OPEN);
        assertThat(vote.isVotingActive()).isTrue();
    }

    @Test
    @DisplayName("應該在結束時間已過時為 Ended")
    void shouldBeEndedWhenEndTimePassed() {
        Vote vote = poll(now.minusDays(2), now.minusDays(1));
        assertThat(vote.getStatus()).isEqualTo(VoteStatus.ENDED);
        assertThat(vote.isVotingActive()).isFalse();
    }

    @Test
    @DisplayName("應該讓 Ended 優先於 Scheduled（結束時間早於開始時間）")
    void shouldPreferEndedOverScheduled() {
        assertThat(poll(now.plusDays(1), now.minusMinutes(1)).getStatus()).isEqualTo(VoteStatus.ENDED);
    }

    @Test
    @DisplayName("應該在 Close Open Poll 時立即結束")
    void shouldEndOpenPollWhenClosed() {
        Vote vote = poll(now.minusHours(1), now.plusDays(1));

        vote.deactivate();

        assertThat(vote.getStatus()).isEqualTo(VoteStatus.ENDED);
        assertThat(vote.getEndTime()).isBefore(now.plusMinutes(1));
        assertThat(vote.isActive()).isFalse();
    }

    @Test
    @DisplayName("應該在 Close Scheduled Poll 時取消，結束時間早於開始時間")
    void shouldEndScheduledPollWhenClosed() {
        OffsetDateTime start = now.plusDays(1);
        Vote vote = poll(start, now.plusDays(2));

        vote.deactivate();

        assertThat(vote.getStatus()).isEqualTo(VoteStatus.ENDED);
        assertThat(vote.getEndTime()).isBefore(start);
    }

    @Test
    @DisplayName("應該在 Close 已結束的 Poll 時不改變結束時間")
    void shouldNotChangeEndTimeWhenClosingEndedPoll() {
        OffsetDateTime end = now.minusDays(1);
        Vote vote = poll(now.minusDays(2), end);

        vote.deactivate();

        assertThat(vote.getEndTime()).isEqualTo(end);
        assertThat(vote.getStatus()).isEqualTo(VoteStatus.ENDED);
    }
}
