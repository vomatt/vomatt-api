package com.vomatt.votes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.entity.Audience;
import com.vomatt.entity.ShareChannel;
import com.vomatt.entity.User;
import com.vomatt.entity.Vote;
import com.vomatt.repository.PostgresRepositoryTest;
import com.vomatt.votes.dto.ShareVoteRequest;

@Import(VoteServiceSlice.class)
@DisplayName("Poll shares (Postgres)")
class VoteSharePostgresTest extends PostgresRepositoryTest {

    @Autowired VoteService voteService;

    private final OffsetDateTime now = OffsetDateTime.now();
    private User owner;
    private User alice;
    private Vote poll;

    @BeforeEach
    void setUp() {
        owner = persistUser("share-owner-" + System.nanoTime());
        alice = persistUser("share-alice-" + System.nanoTime());
        poll = persistPoll(owner, now.minusHours(1), now.plusDays(1), "A", "B");
        em.flush();
        em.clear();
    }

    private long share(Vote vote, User user, ShareChannel channel) {
        long count = voteService.share(vote.getId().toString(), new ShareVoteRequest(channel), user.getId().toString()).shareCount();
        em.flush();
        em.clear();
        return count;
    }

    @Test
    @DisplayName("should count each user once and keep the first channel when the same user shares again")
    void shouldCountDistinctSharersWhenSharedRepeatedly() {
        assertThat(share(poll, alice, ShareChannel.LINE)).isEqualTo(1);
        assertThat(share(poll, alice, ShareChannel.COPY_LINK)).isEqualTo(1);
        assertThat(share(poll, owner, ShareChannel.NATIVE)).isEqualTo(2);

        Object channel = em.createNativeQuery("SELECT channel FROM vomatt.vote_shares WHERE vote_id = :v AND user_id = :u")
                .setParameter("v", poll.getId()).setParameter("u", alice.getId()).getSingleResult();
        assertThat(channel).isEqualTo("LINE");
    }

    @Test
    @DisplayName("should carry shareCount on the Poll and on list items when the Poll was shared")
    void shouldCarryShareCountWhenPollWasShared() {
        Vote other = persistPoll(owner, now.minusHours(1), now.plusDays(1), "A", "B");
        em.flush();
        em.clear();
        share(poll, alice, ShareChannel.X);
        share(poll, owner, ShareChannel.X);

        assertThat(voteService.getVote(poll.getId().toString(), null).getShareCount()).isEqualTo(2);
        assertThat(voteService.toResponses(List.of(em.find(Vote.class, poll.getId()), em.find(Vote.class, other.getId())), null))
                .extracting(r -> r.getShareCount()).containsExactly(2L, 0L);
    }

    @Test
    @DisplayName("should count shares of an Ended Poll")
    void shouldCountShareWhenPollEnded() {
        Vote ended = persistPoll(owner, now.minusDays(2), now.minusHours(1), "A", "B");
        em.flush();
        em.clear();

        assertThat(share(ended, alice, ShareChannel.FACEBOOK)).isEqualTo(1);
    }

    @Test
    @DisplayName("should return vote.not_found when the caller cannot reach the Invite-only Poll")
    void shouldRejectShareWhenPollIsInviteOnlyAndCallerIsNotInvitee() {
        Vote inviteOnly = persistPoll(owner, now.minusHours(1), now.plusDays(1), "A", "B");
        inviteOnly.setAudience(Audience.INVITE_ONLY);
        em.flush();
        em.clear();

        assertThatThrownBy(() -> share(inviteOnly, alice, ShareChannel.LINE))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.VOTE_NOT_FOUND));
    }

    @Test
    @DisplayName("should return vote.not_found when the Poll is Deleted")
    void shouldRejectShareWhenPollIsDeleted() {
        Vote deleted = persistPoll(owner, now.minusHours(1), now.plusDays(1), "A", "B");
        deleted.setDeletedAt(now);
        em.flush();
        em.clear();

        assertThatThrownBy(() -> share(deleted, alice, ShareChannel.LINE))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.VOTE_NOT_FOUND));
    }
}
