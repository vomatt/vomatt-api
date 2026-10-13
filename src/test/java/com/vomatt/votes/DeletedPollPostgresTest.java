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

import com.vomatt.comments.CommentMapper;
import com.vomatt.comments.VoteCommentService;
import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.entity.Tag;
import com.vomatt.entity.User;
import com.vomatt.entity.UserVote;
import com.vomatt.entity.Vote;
import com.vomatt.repository.NotificationReadRepository;
import com.vomatt.repository.PostgresRepositoryTest;
import com.vomatt.repository.UserRepository;
import com.vomatt.votes.dto.VoteResponse;

@Import({ VoteServiceSlice.class, VoteCommentService.class, CommentMapper.class })
@DisplayName("Deleted Polls (Postgres)")
class DeletedPollPostgresTest extends PostgresRepositoryTest {

    @Autowired VoteService voteService;
    @Autowired VoteCommentService commentService;
    @Autowired NotificationReadRepository notificationReadRepository;
    @Autowired UserRepository userRepository;

    private final OffsetDateTime now = OffsetDateTime.now();
    private final String word = "zq" + System.nanoTime();
    private User owner;
    private User voter;
    private Tag tag;
    private Vote deletedOpen;
    private Vote deletedEnded;

    @BeforeEach
    void setUp() {
        owner = persistUser("del-owner-" + System.nanoTime());
        voter = persistUser("del-voter-" + System.nanoTime());
        tag = new Tag("t" + System.nanoTime() % 1_000_000_000, "slug-" + System.nanoTime(), null, 0);
        tag.setUsageCount(4);
        em.persist(tag);

        poll("live-open", now.minusHours(1), now.plusDays(1));
        poll("live-ended", now.minusDays(2), now.minusHours(1));
        deletedOpen = poll("deleted-open", now.minusHours(1), now.plusDays(1));
        deletedEnded = poll("deleted-ended", now.minusDays(2), now.minusHours(1));
        em.flush();

        // Posted just now, so both are inside the Delete window even though they hold Ballots
        voteService.deleteVote(deletedOpen.getId().toString(), owner.getId().toString());
        voteService.deleteVote(deletedEnded.getId().toString(), owner.getId().toString());
        em.flush();
        em.clear();
    }

    private Vote poll(String title, OffsetDateTime start, OffsetDateTime end) {
        Vote vote = persistPoll(owner, start, end, "A", "B");
        vote.setTitle(title + " " + word);
        vote.addTag(tag);
        em.persist(new UserVote(voter, vote, vote.getOptions().iterator().next(), null));
        return vote;
    }

    private static List<String> titles(List<VoteResponse> items) {
        return items.stream().map(v -> v.getTitle().split(" ")[0]).toList();
    }

    @Test
    @DisplayName("should hide Deleted Polls from Feed, Explore and Search when listing")
    void shouldHideDeletedPollsWhenListing() {
        assertThat(titles(voteService.listVotes(VoteListOrder.NEWEST, tag.getSlug(), null, null, 50, null).items()))
                .containsExactly("live-open");
        assertThat(titles(voteService.listVotes(VoteListOrder.CLOSING, tag.getSlug(), null, null, 50, null).items()))
                .containsExactly("live-open");
        assertThat(titles(voteService.listVotes(VoteListOrder.ENDED, tag.getSlug(), null, null, 50, null).items()))
                .containsExactly("live-ended");
        assertThat(titles(voteService.listVotes(VoteListOrder.NEWEST, null, word, null, 50, null).items()))
                .containsExactly("live-open");
    }

    @Test
    @DisplayName("should hide Deleted Polls from the profile list and Poll count when viewing a profile")
    void shouldHideDeletedPollsWhenViewingProfile() {
        assertThat(titles(voteService.getUserPolls(owner.getUsername(), null, 50, null).items()))
                .containsExactly("live-open", "live-ended");
        assertThat(userRepository.findProfileByUsername(owner.getUsername()).orElseThrow().getTotalPolls())
                .isEqualTo(2L);
    }

    @Test
    @DisplayName("should hide Deleted Polls from My Polls and Ended Notifications when the user took part")
    void shouldHideDeletedPollsWhenListingMyPolls() {
        String voterId = voter.getId().toString();
        assertThat(titles(voteService.getMyPolls(voterId, "open", null, 50).items())).containsExactly("live-open");
        assertThat(titles(voteService.getMyPolls(voterId, "ended", null, 50).items())).containsExactly("live-ended");
        assertThat(notificationReadRepository.countUnread(voter.getId(), OffsetDateTime.now())).isEqualTo(1);
        assertThat(notificationReadRepository.isNotified(voter.getId(), deletedEnded.getId(), OffsetDateTime.now()))
                .isFalse();
    }

    @Test
    @DisplayName("should stop counting Deleted Polls in popular tag usage when deleted")
    void shouldReleaseTagUsageWhenDeleted() {
        assertThat(em.find(Tag.class, tag.getId()).getUsageCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("should return vote.not_found for Poll and Comment paths when the Poll is Deleted")
    void shouldReturnNotFoundWhenPollDeleted() {
        String id = deletedOpen.getId().toString();
        String ownerId = owner.getId().toString();

        assertThatThrownBy(() -> voteService.getVote(id, ownerId))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.VOTE_NOT_FOUND));
        assertThatThrownBy(() -> commentService.getCommentsByVote(id, null, null, null))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.VOTE_NOT_FOUND));
    }
}
