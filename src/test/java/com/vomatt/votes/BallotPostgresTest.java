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
import com.vomatt.entity.UserPreference;
import com.vomatt.entity.Vote;
import com.vomatt.entity.VoteOption;
import com.vomatt.repository.PostgresRepositoryTest;
import com.vomatt.repository.UserRepository;
import com.vomatt.repository.VoteOptionRepository;
import com.vomatt.repository.VoteRepository;
import com.vomatt.votes.dto.VoteRequest;
import com.vomatt.votes.dto.VoteResponse;

/**
 * Ballot writes against real Postgres with real commits: stored option counts and Turnout must always equal the Ballot rows.
 */
@Import(VoteServiceSlice.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Ballot counting (Postgres)")
class BallotPostgresTest extends PostgresRepositoryTest {

    @Autowired VoteService voteService;
    @Autowired VoteOptionRepository voteOptionRepository;
    @Autowired VoteRepository voteRepository;
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
        return cast(user, optionId, null);
    }

    private VoteResponse cast(User user, UUID optionId, Boolean anonymous) {
        VoteRequest request = new VoteRequest();
        request.setOptionIds(List.of(optionId.toString()));
        request.setAnonymous(anonymous);
        return voteService.vote(poll.getId().toString(), request, user.getId().toString(), "127.0.0.1");
    }

    private boolean storedAnonymous(User user) {
        return (Boolean) em.createNativeQuery("SELECT anonymous FROM vomatt.user_votes WHERE user_id = :userId AND vote_id = :voteId")
                .setParameter("userId", user.getId()).setParameter("voteId", poll.getId()).getSingleResult();
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
        long actualTurnout = ((Number) em.createNativeQuery("SELECT COUNT(*) FROM vomatt.user_votes WHERE vote_id = :voteId")
                .setParameter("voteId", poll.getId()).getSingleResult()).longValue();
        assertThat((long) turnout()).as("stored vs actual Turnout").isEqualTo(actualTurnout);
    }

    private int turnout() {
        return ((Number) em.createNativeQuery("SELECT turnout FROM vomatt.votes WHERE id = :voteId")
                .setParameter("voteId", poll.getId()).getSingleResult()).intValue();
    }

    @Test
    @DisplayName("should replace whole Ballot on revote, decrementing old option and incrementing new one")
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
        assertThat(turnout()).isEqualTo(1);
        assertStoredMatchesActual();
    }

    @Test
    @DisplayName("should not double count when voting the same option again")
    void shouldNotDoubleCountWhenSameOptionCastTwice() {
        cast(voter, optionA);
        cast(voter, optionA);

        assertThat(counts().get(optionA)[0]).isEqualTo(1);
        assertThat(turnout()).isEqualTo(1);
        assertStoredMatchesActual();
    }

    @Test
    @DisplayName("should use the account default when the Ballot omits anonymous, and the Ballot's flag when it sends one")
    void shouldUseAccountDefaultWhenBallotOmitsAnonymous() {
        tx.executeWithoutResult(s -> {
            UserPreference pref = new UserPreference();
            pref.setUser(em.find(User.class, voter.getId()));
            pref.setKey(UserPreference.ANONYMOUS_BALLOT_KEY);
            pref.setValue("true");
            em.persist(pref);
        });

        cast(voter, optionA);
        assertThat(storedAnonymous(voter)).isTrue();

        cast(voter, optionB, false);
        assertThat(storedAnonymous(voter)).isFalse();
    }

    @Test
    @DisplayName("should update only the anonymous flag when the same option is cast again")
    void shouldUpdateAnonymousWhenSameOptionCastAgain() {
        cast(voter, optionA);
        assertThat(storedAnonymous(voter)).isFalse();

        cast(voter, optionA, true);

        assertThat(storedAnonymous(voter)).isTrue();
        assertThat(counts().get(optionA)[0]).isEqualTo(1);
        assertThat(turnout()).isEqualTo(1);
        assertStoredMatchesActual();
    }

    @Test
    @DisplayName("should keep the Ballot Anonymous when a recast or change of option omits anonymous")
    void shouldKeepAnonymousWhenRecastOmitsFlag() {
        cast(voter, optionA, true);

        cast(voter, optionA);
        assertThat(storedAnonymous(voter)).isTrue();

        cast(voter, optionB);
        assertThat(storedAnonymous(voter)).isTrue();
    }

    @Test
    @DisplayName("should keep a single Ballot with consistent counts when same user votes concurrently")
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
        assertThat(turnout()).isEqualTo(1);
        assertStoredMatchesActual();
    }

    @Test
    @DisplayName("should count every Participant without deadlock when different users cast concurrently")
    void shouldCountTurnoutWhenDifferentUsersCastConcurrently() throws Exception {
        List<User> voters = new ArrayList<>();
        tx.executeWithoutResult(s -> {
            for (int i = 0; i < 12; i++) {
                voters.add(track(persistUser("crowd-" + UUID.randomUUID())));
            }
        });
        List<Callable<Object>> calls = new ArrayList<>();
        for (int i = 0; i < voters.size(); i++) {
            User user = voters.get(i);
            UUID option = i % 2 == 0 ? optionA : optionB;
            calls.add(() -> cast(user, option));
        }
        try (var pool = Executors.newFixedThreadPool(8)) {
            for (Future<Object> f : pool.invokeAll(calls)) {
                f.get();
            }
        }

        assertThat(turnout()).isEqualTo(voters.size());
        assertStoredMatchesActual();
    }

    @Test
    @DisplayName("should delete whole Ballot and release counts on withdraw")
    void shouldRetractBallotAndReleaseCount() {
        cast(voter, optionA);

        VoteResponse response = voteService.retract(poll.getId().toString(), voter.getId().toString());

        assertThat(response.getMyOptionId()).isNull();
        assertThat(response.getTotalVotes()).isZero();
        assertThat(response.getOptions()).extracting(VoteResponse.VoteOptionResponse::getVotes).containsOnlyNulls();
        assertThat(counts().get(optionA)[0]).isZero();
        assertThat(turnout()).isZero();
        assertStoredMatchesActual();
    }

    @Test
    @DisplayName("should release Turnout when removing the Selection")
    void shouldReleaseTurnoutWhenSelectionRemoved() {
        cast(voter, optionA);

        voteService.removeVote(poll.getId().toString(), optionA.toString(), voter.getId().toString());

        assertThat(turnout()).isZero();
        assertStoredMatchesActual();
    }

    @Test
    @DisplayName("should succeed without changing counts when withdrawing with no Ballot")
    void shouldRetractIdempotentlyWhenNoBallot() {
        voteService.retract(poll.getId().toString(), voter.getId().toString());
        voteService.retract(poll.getId().toString(), voter.getId().toString());

        counts().values().forEach(c -> assertThat(c[0]).isZero());
        assertThat(turnout()).isZero();
    }

    @Test
    @DisplayName("should keep counts consistent after voter deletes account")
    void shouldKeepCountsConsistentWhenVoterAccountDeleted() {
        cast(voter, optionA);

        tx.executeWithoutResult(s -> {
            voteRepository.decrementTurnoutForUserBallots(voter.getId());
            voteOptionRepository.decrementForUserBallots(voter.getId());
            userRepository.deleteById(voter.getId());
        });

        assertThat(counts().get(optionA)[0]).isZero();
        assertThat(turnout()).isZero();
        assertStoredMatchesActual();
    }
}
