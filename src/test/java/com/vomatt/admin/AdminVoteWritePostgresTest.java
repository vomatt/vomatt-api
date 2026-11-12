package com.vomatt.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import com.vomatt.entity.Audience;
import com.vomatt.entity.User;
import com.vomatt.entity.Vote;
import com.vomatt.admin.dto.AdminVoteResponse;
import com.vomatt.repository.AdminQueryRepository;
import com.vomatt.repository.PostgresRepositoryTest;
import com.vomatt.votes.VoteServiceSlice;

/** Admin Close / Restore answer with the Poll's new updatedAt, as a later read shows it. */
@Import({ VoteServiceSlice.class, AdminQueryRepository.class, AdminVoteService.class })
class AdminVoteWritePostgresTest extends PostgresRepositoryTest {

    @Autowired AdminVoteService adminVoteService;

    private final OffsetDateTime now = OffsetDateTime.now();

    private Vote openUnlistedPoll() {
        User owner = persistUser("avw-" + System.nanoTime());
        Vote poll = persistPoll(owner, now.minusHours(1), now.plusDays(1), Audience.UNLISTED, "A", "B");
        em.flush();
        em.clear();
        return poll;
    }

    private void assertUpdatedAtStored(UUID pollId, AdminVoteResponse written, OffsetDateTime before) {
        em.flush();
        em.clear();
        OffsetDateTime stored = adminVoteService.getVote(pollId).vote().getUpdatedAt();
        assertThat(written.vote().getUpdatedAt()).isAfter(before);
        assertThat(written.vote().getUpdatedAt().toInstant()).isCloseTo(stored.toInstant(),
                org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.MILLIS));
    }

    @Test
    void shouldAnswerNewUpdatedAtWhenAdminClosesPoll() {
        Vote poll = openUnlistedPoll();

        AdminVoteResponse closed = adminVoteService.closeVote(poll.getId(), "admin");

        assertUpdatedAtStored(poll.getId(), closed, poll.getUpdatedAt());
    }

    @Test
    void shouldAnswerNewUpdatedAtWhenAdminRestoresPoll() {
        Vote poll = openUnlistedPoll();
        adminVoteService.deleteVote(poll.getId(), "admin");
        em.flush();
        em.clear();
        OffsetDateTime deletedAt = adminVoteService.getVote(poll.getId()).vote().getUpdatedAt();

        AdminVoteResponse restored = adminVoteService.restoreVote(poll.getId(), "admin");

        assertUpdatedAtStored(poll.getId(), restored, deletedAt);
    }
}
