package com.vomatt.votes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import com.vomatt.admin.AdminVoteService;
import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.entity.User;
import com.vomatt.entity.UserVote;
import com.vomatt.entity.Vote;
import com.vomatt.entity.VoteOption;
import com.vomatt.entity.VoterVisibility;
import com.vomatt.repository.AdminQueryRepository;
import com.vomatt.repository.PostgresRepositoryTest;
import com.vomatt.votes.dto.CreateVoteRequest;
import com.vomatt.votes.dto.VoteResponse;

@Import({ VoteServiceSlice.class, AdminVoteService.class, AdminQueryRepository.class })
@DisplayName("Pro polls and reveal time (Postgres)")
class ProPollPostgresTest extends PostgresRepositoryTest {

    @Autowired VoteService voteService;
    @Autowired AdminVoteService adminVoteService;

    // Postgres keeps microseconds
    private final OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
    private User owner;
    private String ownerId;

    @BeforeEach
    void setUp() {
        owner = persistUser("pro-owner-" + System.nanoTime());
        ownerId = owner.getId().toString();
    }

    private static void assertApiError(Throwable ex, MessageKey key) {
        assertThat(ex).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessageKey()).isEqualTo(key));
    }

    private CreateVoteRequest request(OffsetDateTime end, OffsetDateTime revealAt) {
        CreateVoteRequest request = new CreateVoteRequest();
        request.setTitle("pro " + System.nanoTime());
        request.setOptions(IntStream.range(0, 2).mapToObj(i -> {
            CreateVoteRequest.VoteOptionRequest option = new CreateVoteRequest.VoteOptionRequest();
            option.setText("opt" + i);
            option.setDisplayOrder(i);
            return option;
        }).toList());
        request.setStartTime(now.plusHours(1));
        request.setEndTime(end);
        request.setRevealAt(revealAt);
        return request;
    }

    /** An Ended Pro poll with one named and one (pre-Pro) Anonymous Ballot, counts stored as the Ballot path would. */
    private Vote endedProPoll(OffsetDateTime revealAt) {
        Vote poll = persistPoll(owner, now.minusDays(2), now.minusDays(1), "A", "B");
        VoteOption a = poll.getOptions().stream().filter(o -> o.getText().equals("A")).findFirst().orElseThrow();
        em.persist(new UserVote(persistUser("pro-named-" + System.nanoTime()), poll, a));
        UserVote anonymous = new UserVote(persistUser("pro-anon-" + System.nanoTime()), poll, a);
        anonymous.setAnonymous(true);
        em.persist(anonymous);
        em.flush();
        em.createNativeQuery("UPDATE vomatt.vote_options SET vote_count = 2 WHERE id = :id").setParameter("id", a.getId()).executeUpdate();
        em.createNativeQuery("UPDATE vomatt.votes SET turnout = 2, pro = TRUE, voter_visibility = 'NOBODY', is_anonymous = TRUE, reveal_at = :revealAt WHERE id = :id")
                .setParameter("revealAt", revealAt).setParameter("id", poll.getId()).executeUpdate();
        em.clear();
        return poll;
    }

    private static List<Long> counts(VoteResponse response) {
        return response.getOptions().stream().map(VoteResponse.VoteOptionResponse::getVotes).toList();
    }

    @Test
    void shouldSealEveryResultPathWhenEndedProPollBeforeRevealAt() {
        String pollId = endedProPoll(now.plusHours(1)).getId().toString();

        VoteResponse read = voteService.getVote(pollId, ownerId);
        assertThat(counts(read)).containsOnlyNulls();
        assertThat(read.getParticipantCount()).isEqualTo(2);
        assertThat(read.isPro()).isTrue();
        assertThat(counts(adminVoteService.getVote(UUID.fromString(pollId)).vote())).containsOnlyNulls();
        assertThat(voteService.listVotes(VoteListOrder.ENDED, null, null, null, 50, ownerId).items())
                .filteredOn(v -> v.getId().equals(pollId)).singleElement()
                .satisfies(v -> assertThat(counts(v)).containsOnlyNulls());
        assertThatThrownBy(() -> voteService.getVoteResults(pollId, ownerId))
                .satisfies(ex -> assertApiError(ex, MessageKey.VOTE_RESULTS_SEALED));
        assertThatThrownBy(() -> voteService.getVoters(pollId, ownerId, null, null))
                .satisfies(ex -> assertApiError(ex, MessageKey.VOTE_RESULTS_SEALED));
    }

    @Test
    void shouldShowResultsLikeEndedNobodyPollWhenRevealAtPassed() {
        String pollId = endedProPoll(now.minusHours(1)).getId().toString();

        assertThat(counts(voteService.getVote(pollId, ownerId))).containsExactly(2L, 0L);
        assertThat(voteService.getVoteResults(pollId, null).getOptions())
                .extracting(o -> o.getVoteCount()).containsExactly(2L, 0L);
        // NOBODY: the voter list stays refused for everyone, owner included, so no optionId is ever named
        assertThatThrownBy(() -> voteService.getVoters(pollId, ownerId, null, null))
                .satisfies(ex -> assertApiError(ex, MessageKey.VOTE_VOTERS_HIDDEN));
    }

    @Test
    void shouldRejectEditWhenRevealAtBeforeEndTime() {
        OffsetDateTime end = now.plusDays(1);
        String pollId = voteService.createVote(request(end, end.plusHours(1)), ownerId).getId();
        em.flush();
        em.clear();

        assertThatThrownBy(() -> voteService.updateVote(pollId, request(end, end.minusMinutes(1)), ownerId))
                .satisfies(ex -> assertApiError(ex, MessageKey.VOTE_REVEAL_BEFORE_END));
    }

    @Test
    void shouldKeepStoredRevealAtWhenEditOmitsIt() {
        OffsetDateTime end = now.plusDays(1);
        OffsetDateTime revealAt = end.plusHours(1);
        String pollId = voteService.createVote(request(end, revealAt), ownerId).getId();
        em.flush();
        em.clear();

        VoteResponse edited = voteService.updateVote(pollId, request(end, null), ownerId);
        assertThat(edited.getRevealAt().toInstant()).isEqualTo(revealAt.toInstant());
        em.flush();
        em.clear();
        assertThat(voteService.getVote(pollId, ownerId).getRevealAt().toInstant()).isEqualTo(revealAt.toInstant());

        // The kept reveal time is checked against the new end time too
        em.clear();
        assertThatThrownBy(() -> voteService.updateVote(pollId, request(revealAt.plusHours(1), null), ownerId))
                .satisfies(ex -> assertApiError(ex, MessageKey.VOTE_REVEAL_BEFORE_END));
    }

    @Test
    void shouldStayNobodyWhenProPollEditedWithOwnerVisibility() {
        CreateVoteRequest create = request(now.plusDays(1), null);
        create.setVoterVisibility(VoterVisibility.OWNER);
        String pollId = voteService.createVote(create, ownerId).getId();
        em.flush();
        em.clear();

        VoteResponse switched = adminVoteService.setPro(UUID.fromString(pollId), true, UUID.randomUUID().toString()).vote();
        assertThat(switched.isPro()).isTrue();
        assertThat(switched.getVoterVisibility()).isEqualTo(VoterVisibility.NOBODY);
        em.flush();
        em.clear();

        CreateVoteRequest edit = request(now.plusDays(1), null);
        edit.setVoterVisibility(VoterVisibility.OWNER);
        VoteResponse edited = voteService.updateVote(pollId, edit, ownerId);
        assertThat(edited.getVoterVisibility()).isEqualTo(VoterVisibility.NOBODY);
        assertThat(edited.isAnonymous()).isTrue();
        em.flush();
        em.clear();

        CreateVoteRequest legacy = request(now.plusDays(1), null);
        legacy.setAnonymous(false);
        assertThat(voteService.updateVote(pollId, legacy, ownerId).getVoterVisibility()).isEqualTo(VoterVisibility.NOBODY);
    }
}
