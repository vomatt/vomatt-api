package com.vomatt.votes;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import com.vomatt.entity.User;
import com.vomatt.entity.UserVote;
import com.vomatt.entity.Vote;
import com.vomatt.repository.PostgresRepositoryTest;
import com.vomatt.votes.dto.VoteResponse;

@Import(VoteServiceSlice.class)
@DisplayName("My Polls (Postgres)")
class MyPollsPostgresTest extends PostgresRepositoryTest {

    @Autowired VoteService voteService;

    private final OffsetDateTime now = OffsetDateTime.now();
    private User me;
    private User other;

    @BeforeEach
    void setUp() {
        me = persistUser("me-" + System.nanoTime());
        other = persistUser("other-" + System.nanoTime());
    }

    private Vote poll(User owner, String title, OffsetDateTime start, OffsetDateTime end) {
        Vote vote = persistPoll(owner, start, end, "A", "B");
        vote.setTitle(title);
        return vote;
    }

    private void voteIn(Vote poll) {
        em.persist(new UserVote(me, poll, poll.getOptions().iterator().next(), null));
    }

    private List<String> titles(String status) {
        em.flush();
        em.clear();
        return voteService.getMyPolls(me.getId().toString(), status, null, null).items().stream()
                .map(VoteResponse::getTitle).toList();
    }

    @Test
    @DisplayName("應該讓 open 分頁含我建立與我投過的 Poll、含我的 Scheduled，依即將截止排序")
    void shouldIncludeParticipatedPollsInMyPolls() {
        poll(me, "mine-scheduled", now.plusHours(1), now.plusDays(3));
        poll(me, "mine-open", now.minusHours(1), now.plusDays(2));
        voteIn(poll(other, "voted-open", now.minusHours(1), now.plusHours(5)));
        poll(other, "unrelated", now.minusHours(1), now.plusHours(1));
        poll(me, "mine-ended", now.minusDays(2), now.minusDays(1));

        assertThat(titles(null)).containsExactly("voted-open", "mine-open", "mine-scheduled");
    }

    @Test
    @DisplayName("應該讓 ended 分頁依結束時間新到舊")
    void shouldListEndedPollsMostRecentFirst() {
        poll(me, "mine-ended-long-ago", now.minusDays(9), now.minusDays(5));
        voteIn(poll(other, "voted-ended-recently", now.minusDays(9), now.minusHours(1)));
        poll(other, "unrelated-ended", now.minusDays(9), now.minusHours(2));
        poll(me, "mine-open", now.minusHours(1), now.plusDays(1));

        assertThat(titles("ended")).containsExactly("voted-ended-recently", "mine-ended-long-ago");
    }
}
