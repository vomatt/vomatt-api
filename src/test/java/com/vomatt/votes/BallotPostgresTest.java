package com.vomatt.votes;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.vomatt.entity.User;
import com.vomatt.entity.Vote;
import com.vomatt.entity.VoteOption;
import com.vomatt.repository.PostgresRepositoryTest;
import com.vomatt.repository.UserRepository;
import com.vomatt.repository.VoteOptionRepository;
import com.vomatt.votes.dto.VoteRequest;
import com.vomatt.votes.dto.VoteResponse;

/**
 * Ballot writes against real Postgres with real commits: stored option counts must always equal the Ballot rows.
 */
@Import(VoteServiceSlice.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Ballot counting (Postgres)")
class BallotPostgresTest extends PostgresRepositoryTest {

    @Autowired VoteService voteService;
    @Autowired VoteOptionRepository voteOptionRepository;
    @Autowired UserRepository userRepository;
    @Autowired TransactionTemplate tx;

    private final List<UUID> createdUsers = new ArrayList<>();
    private User voter;
    private Vote poll;
    private UUID optionA;
    private UUID optionB;

    @BeforeEach
    void setUp() {
        tx.executeWithoutResult(s -> {
            User owner = track(persistUser("owner-" + UUID.randomUUID()));
            voter = track(persistUser("voter-" + UUID.randomUUID()));
            OffsetDateTime now = OffsetDateTime.now();
            poll = persistPoll(owner, now.minusHours(1), now.plusDays(1), "A", "B");
        });
        List<VoteOption> options = voteOptionRepository.findByVoteIdOrderByDisplayOrder(poll.getId());
        optionA = options.get(0).getId();
        optionB = options.get(1).getId();
    }

    @AfterEach
    void cleanUp() {
        // polls, options and ballots cascade with their users
        tx.executeWithoutResult(s -> em.createNativeQuery("DELETE FROM vomatt.users WHERE id IN (:ids)")
                .setParameter("ids", createdUsers).executeUpdate());
    }

    private User track(User user) {
        createdUsers.add(user.getId());
        return user;
    }

    private VoteResponse cast(User user, UUID optionId) {
        VoteRequest request = new VoteRequest();
        request.setOptionIds(List.of(optionId.toString()));
        return voteService.vote(poll.getId().toString(), request, user.getId().toString(), "127.0.0.1");
    }

    // option id -> {stored vote_count, actual Ballot rows}
    private Map<UUID, long[]> counts() {
        Map<UUID, long[]> result = new java.util.HashMap<>();
        List<?> rows = em.createNativeQuery("""
                SELECT o.id, o.vote_count, (SELECT COUNT(*) FROM vomatt.user_votes uv WHERE uv.option_id = o.id)
                FROM vomatt.vote_options o WHERE o.vote_id = :voteId""")
                .setParameter("voteId", poll.getId()).getResultList();
        for (Object row : rows) {
            Object[] r = (Object[]) row;
            result.put((UUID) r[0], new long[] { ((Number) r[1]).longValue(), ((Number) r[2]).longValue() });
        }
        return result;
    }

    private void assertStoredMatchesActual() {
        counts().values().forEach(c -> assertThat(c[0]).as("stored vs actual").isEqualTo(c[1]));
    }

    @Test
    @DisplayName("應該在再次投票時整張取代 Ballot，舊選項減一、新選項加一")
    void shouldReplaceBallotWhenUserVotesAgain() {
        cast(voter, optionA);
        VoteResponse response = cast(voter, optionB);

        assertThat(response.getMyOptionId()).isEqualTo(optionB.toString());
        assertThat(response.getTotalVotes()).isEqualTo(1);
        assertThat(response.getParticipantCount()).isEqualTo(1);
        // Open Poll: the caster sees only their own Ballot, not per-option counts
        assertThat(response.getOptions()).extracting(VoteResponse.VoteOptionResponse::getVotes).containsOnlyNulls();
        Map<UUID, long[]> counts = counts();
        assertThat(counts.get(optionA)[0]).isZero();
        assertThat(counts.get(optionB)[0]).isEqualTo(1);
        assertStoredMatchesActual();
    }

    @Test
    @DisplayName("應該在重複投同一選項時不重複計數")
    void shouldNotDoubleCountWhenSameOptionCastTwice() {
        cast(voter, optionA);
        cast(voter, optionA);

        assertThat(counts().get(optionA)[0]).isEqualTo(1);
        assertStoredMatchesActual();
    }

    @Test
    @DisplayName("應該在同一使用者併發投票時只留下一張 Ballot 且計數一致")
    void shouldKeepSingleBallotWhenSameUserCastsConcurrently() throws Exception {
        List<Callable<Object>> calls = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            UUID option = i % 2 == 0 ? optionA : optionB;
            calls.add(() -> cast(voter, option));
        }
        try (var pool = Executors.newFixedThreadPool(8)) {
            for (Future<Object> f : pool.invokeAll(calls)) {
                f.get();
            }
        }

        Map<UUID, long[]> counts = counts();
        assertThat(counts.get(optionA)[1] + counts.get(optionB)[1]).isEqualTo(1);
        assertStoredMatchesActual();
    }

    @Test
    @DisplayName("應該在撤回時刪除整張 Ballot 並釋放計數")
    void shouldRetractBallotAndReleaseCount() {
        cast(voter, optionA);

        VoteResponse response = voteService.retract(poll.getId().toString(), voter.getId().toString());

        assertThat(response.getMyOptionId()).isNull();
        assertThat(response.getTotalVotes()).isZero();
        assertThat(response.getOptions()).extracting(VoteResponse.VoteOptionResponse::getVotes).containsOnlyNulls();
        assertThat(counts().get(optionA)[0]).isZero();
        assertStoredMatchesActual();
    }

    @Test
    @DisplayName("應該在沒有 Ballot 時撤回仍成功且不改變計數")
    void shouldRetractIdempotentlyWhenNoBallot() {
        voteService.retract(poll.getId().toString(), voter.getId().toString());
        voteService.retract(poll.getId().toString(), voter.getId().toString());

        counts().values().forEach(c -> assertThat(c[0]).isZero());
    }

    @Test
    @DisplayName("應該在投票者刪除帳號後仍維持計數一致")
    void shouldKeepCountsConsistentWhenVoterAccountDeleted() {
        cast(voter, optionA);

        tx.executeWithoutResult(s -> {
            voteOptionRepository.decrementForUserBallots(voter.getId());
            userRepository.deleteById(voter.getId());
        });

        assertThat(counts().get(optionA)[0]).isZero();
        assertStoredMatchesActual();
    }
}
