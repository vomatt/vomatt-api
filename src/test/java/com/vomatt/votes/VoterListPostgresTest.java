package com.vomatt.votes;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import com.vomatt.common.response.CursorResponse;
import com.vomatt.entity.User;
import com.vomatt.entity.UserVote;
import com.vomatt.entity.Vote;
import com.vomatt.entity.VoteOption;
import com.vomatt.repository.PostgresRepositoryTest;
import com.vomatt.votes.dto.VoterResponse;

@Import({ VoteService.class, VoteMapper.class, VoteConfigurationProperties.class })
@DisplayName("Voter list (Postgres)")
class VoterListPostgresTest extends PostgresRepositoryTest {

    @Autowired VoteService voteService;

    @Test
    @DisplayName("應該在 cursor 分頁時不重複也不遺漏投票者")
    void shouldNotRepeatVotersAcrossCursorPages() {
        User owner = persistUser("vl-owner");
        OffsetDateTime now = OffsetDateTime.now();
        Vote poll = persistPoll(owner, now.minusDays(2), now.minusDays(1), "A", "B");
        VoteOption option = poll.getOptions().iterator().next();
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            User voter = persistUser("vl-voter-" + i);
            em.persist(new UserVote(voter, poll, option, null));
            expected.add(voter.getUsername());
        }
        em.flush();
        em.clear();

        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            CursorResponse<VoterResponse> page = voteService.getVoters(poll.getId().toString(),
                    owner.getId().toString(), cursor, 2);
            page.items().forEach(v -> seen.add(v.username()));
            cursor = page.nextCursor();
            pages++;
        } while (cursor != null);

        assertThat(pages).isEqualTo(3);
        assertThat(seen).containsExactlyInAnyOrderElementsOf(expected).doesNotHaveDuplicates();
    }
}
