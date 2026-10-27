package com.vomatt.votes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import com.vomatt.entity.Audience;
import com.vomatt.entity.User;
import com.vomatt.entity.Vote;
import com.vomatt.repository.PostgresRepositoryTest;
import com.vomatt.repository.VoteRepository;

@Import(VoteServiceSlice.class)
@DisplayName("Poll Views (Postgres)")
class VoteViewPostgresTest extends PostgresRepositoryTest {

    @Autowired VoteService voteService;
    @Autowired VoteRepository voteRepository;
    @Autowired VoteViewRecorder viewRecorder;

    private final OffsetDateTime now = OffsetDateTime.now();
    private User owner;
    private User alice;

    @BeforeEach
    void setUp() {
        owner = persistUser("view-owner-" + System.nanoTime());
        alice = persistUser("view-alice-" + System.nanoTime());
    }

    // The slice's recorder mock is shared with other test classes
    @AfterEach
    void resetRecorder() {
        reset(viewRecorder);
    }

    private Vote poll(OffsetDateTime startTime, Audience audience) {
        Vote poll = persistPoll(owner, startTime, now.plusDays(1), "A", "B");
        poll.setAudience(audience);
        em.flush();
        em.clear();
        return poll;
    }

    private void view(Vote poll, User viewer) {
        voteService.getVote(poll.getId().toString(), viewer == null ? null : viewer.getId().toString());
    }

    private long views(Vote poll) {
        return ((Number) em.createNativeQuery("SELECT COALESCE(SUM(views), 0) FROM vomatt.vote_view_hours WHERE vote_id = :v")
                .setParameter("v", poll.getId()).getSingleResult()).longValue();
    }

    @Test
    @DisplayName("should record a signed-in non-owner's View of an Open Public Poll")
    void shouldRecordViewWhenNonOwnerOpensOpenPublicPoll() {
        Vote poll = poll(now.minusHours(1), Audience.PUBLIC);

        view(poll, alice);

        verify(viewRecorder).record(poll.getId(), alice.getId());
    }

    @Test
    @DisplayName("should not record the owner, a signed-out viewer, or a Poll Trending cannot list")
    void shouldNotRecordViewWhenViewerIsOwnerOrSignedOutOrPollIsNotOpenPublic() {
        Vote open = poll(now.minusHours(1), Audience.PUBLIC);
        Vote unlisted = poll(now.minusHours(1), Audience.UNLISTED);
        Vote scheduled = poll(now.plusHours(1), Audience.PUBLIC);

        view(open, owner);
        view(open, null);
        view(unlisted, alice);
        view(scheduled, alice);

        verify(viewRecorder, never()).record(any(), any());
    }

    @Test
    @DisplayName("should add to the current hour's single bucket and remove it with its Poll")
    void shouldUpsertCurrentHourBucketAndCascadeWhenPollRemoved() {
        Vote poll = poll(now.minusHours(1), Audience.PUBLIC);

        voteRepository.incrementViewHour(poll.getId());
        voteRepository.incrementViewHour(poll.getId());

        assertThat(views(poll)).isEqualTo(2);
        assertThat(((Number) em.createNativeQuery("SELECT COUNT(*) FROM vomatt.vote_view_hours WHERE vote_id = :v")
                .setParameter("v", poll.getId()).getSingleResult()).longValue()).isEqualTo(1);

        em.createNativeQuery("DELETE FROM vomatt.votes WHERE id = :v").setParameter("v", poll.getId()).executeUpdate();

        assertThat(views(poll)).isZero();
    }
}
