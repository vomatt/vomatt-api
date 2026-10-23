package com.vomatt.votes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.response.CursorResponse;
import com.vomatt.entity.User;
import com.vomatt.entity.UserVote;
import com.vomatt.entity.Vote;
import com.vomatt.entity.VoteOption;
import com.vomatt.repository.PostgresRepositoryTest;
import com.vomatt.votes.dto.VoterResponse;

@Import(VoteServiceSlice.class)
@DisplayName("Voter list (Postgres)")
class VoterListPostgresTest extends PostgresRepositoryTest {

    @Autowired VoteService voteService;

    @Test
    @DisplayName("should paginate voters by cursor without duplicates or gaps")
    void shouldNotRepeatVotersAcrossCursorPages() {
        User owner = persistUser("vl-owner");
        OffsetDateTime now = OffsetDateTime.now();
        Vote poll = persistPoll(owner, now.minusDays(2), now.minusDays(1), "A", "B");
        VoteOption option = poll.getOptions().iterator().next();
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            User voter = persistUser("vl-voter-" + i);
            em.persist(new UserVote(voter, poll, option));
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

    @Test
    @DisplayName("should carry the owner's and each Participant's avatar on the Poll, its results and its voters")
    void shouldCarryAvatarsWhenPollEnded() {
        User owner = persistUser("vl-av-owner");
        owner.setAvatarUrl("bot:boba");
        OffsetDateTime now = OffsetDateTime.now();
        Vote poll = persistPoll(owner, now.minusDays(2), now.minusDays(1), "A", "B");
        User voter = persistUser("vl-av-voter");
        voter.setAvatarUrl("bot:mochi");
        em.persist(new UserVote(voter, poll, poll.getOptions().iterator().next()));
        em.flush();
        em.clear();
        String pollId = poll.getId().toString();
        String ownerId = owner.getId().toString();

        assertThat(voteService.getVote(pollId, ownerId).getCreatorAvatarUrl()).isEqualTo("bot:boba");
        assertThat(voteService.getVoteResults(pollId, ownerId).getCreatorAvatarUrl()).isEqualTo("bot:boba");
        assertThat(voteService.getVoters(pollId, ownerId, null, null).items())
                .extracting(VoterResponse::avatarUrl).containsExactly("bot:mochi");
    }

    @Test
    @DisplayName("should list named Participants without any option while another user's Anonymous Ballot exists")
    void shouldDropOptionsFromVoterListWhenAnonymousBallotsExist() {
        User owner = persistUser("vl-anon-owner");
        OffsetDateTime now = OffsetDateTime.now();
        Vote poll = persistPoll(owner, now.minusDays(2), now.minusDays(1), "A", "B");
        VoteOption option = poll.getOptions().iterator().next();
        em.persist(new UserVote(persistUser("vl-anon-named"), poll, option));
        persistAnonymousBallot(persistUser("vl-anon-hidden-0"), poll, option);
        em.flush();
        em.clear();

        assertThat(voteService.getVoters(poll.getId().toString(), owner.getId().toString(), null, null).items())
                .extracting(VoterResponse::username, VoterResponse::optionId)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("vl-anon-named", null));
    }

    @Test
    @DisplayName("should name options when the only Anonymous Ballot is the caller's own")
    void shouldKeepOptionsWhenOnlyTheCallersBallotIsAnonymous() {
        User owner = persistUser("vl-self-owner");
        OffsetDateTime now = OffsetDateTime.now();
        Vote poll = persistPoll(owner, now.minusDays(2), now.minusDays(1), "A", "B");
        VoteOption option = poll.getOptions().iterator().next();
        persistAnonymousBallot(owner, poll, option);
        em.persist(new UserVote(persistUser("vl-self-named"), poll, option));
        em.flush();
        em.clear();

        assertThat(voteService.getVoters(poll.getId().toString(), owner.getId().toString(), null, null).items())
                .extracting(VoterResponse::username, VoterResponse::optionId)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("vl-self-named", option.getId().toString()));
    }

    private void persistAnonymousBallot(User user, Vote poll, VoteOption option) {
        UserVote ballot = new UserVote(user, poll, option);
        ballot.setAnonymous(true);
        em.persist(ballot);
    }
}
