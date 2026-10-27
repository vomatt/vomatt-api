package com.vomatt.votes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import com.vomatt.common.redis.RedisOperationException;
import com.vomatt.common.redis.RedisService;
import com.vomatt.entity.User;
import com.vomatt.entity.Vote;
import com.vomatt.repository.PostgresRepositoryTest;
import com.vomatt.votes.dto.VoteResponse;

@Import(VoteServiceSlice.class)
@DisplayName("Poll Views (Postgres)")
class VoteViewPostgresTest extends PostgresRepositoryTest {

    @Autowired VoteService voteService;
    @Autowired RedisService redisService;

    private final OffsetDateTime now = OffsetDateTime.now();
    private User owner;
    private User alice;
    private Vote poll;

    @BeforeEach
    void setUp() {
        owner = persistUser("view-owner-" + System.nanoTime());
        alice = persistUser("view-alice-" + System.nanoTime());
        poll = persistPoll(owner, now.minusHours(1), now.plusDays(1), "A", "B");
        em.flush();
        em.clear();
    }

    // The slice's RedisService mock is shared with other test classes
    @AfterEach
    void resetRedis() {
        reset(redisService);
    }

    private void view(User viewer) {
        VoteResponse response = voteService.getVote(poll.getId().toString(), viewer == null ? null : viewer.getId().toString());
        voteService.recordView(response, viewer == null ? null : viewer.getId().toString());
        em.flush();
        em.clear();
    }

    private long views() {
        return ((Number) em.createNativeQuery("SELECT COALESCE(SUM(views), 0) FROM vomatt.vote_view_hours WHERE vote_id = :v")
                .setParameter("v", poll.getId()).getSingleResult()).longValue();
    }

    @Test
    @DisplayName("should add to the current hour's bucket only when the dedupe key is new")
    void shouldCountViewOnceWhenDedupeKeyAlreadySet() {
        when(redisService.setIfAbsent(eq("vote:view"), anyString(), any(), eq(VoteService.VIEW_DEDUPE_WINDOW)))
                .thenReturn(true, true, false);

        view(alice);
        view(alice);
        view(alice);

        assertThat(views()).isEqualTo(2);
        assertThat(((Number) em.createNativeQuery("SELECT COUNT(*) FROM vomatt.vote_view_hours WHERE vote_id = :v")
                .setParameter("v", poll.getId()).getSingleResult()).longValue()).isEqualTo(1);
        verify(redisService, times(3))
                .setIfAbsent("vote:view", poll.getId() + ":" + alice.getId(), 1, VoteService.VIEW_DEDUPE_WINDOW);
    }

    @Test
    @DisplayName("should not count the owner or a signed-out viewer")
    void shouldNotCountViewWhenViewerIsOwnerOrSignedOut() {
        view(owner);
        view(null);

        assertThat(views()).isZero();
        verify(redisService, never()).setIfAbsent(anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("should drop the View without failing when Redis is down")
    void shouldDropViewWhenRedisFails() {
        when(redisService.setIfAbsent(anyString(), anyString(), any(), any()))
                .thenThrow(new RedisOperationException("down", null));

        view(alice);

        assertThat(views()).isZero();
    }

    @Test
    @DisplayName("should remove the buckets with their Poll")
    void shouldCascadeViewHoursWhenPollRemoved() {
        when(redisService.setIfAbsent(anyString(), anyString(), any(), any())).thenReturn(true);
        view(alice);

        em.createNativeQuery("DELETE FROM vomatt.votes WHERE id = :v").setParameter("v", poll.getId()).executeUpdate();

        assertThat(views()).isZero();
    }
}
