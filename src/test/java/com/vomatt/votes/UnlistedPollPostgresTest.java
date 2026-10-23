package com.vomatt.votes;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import com.vomatt.comments.CommentMapper;
import com.vomatt.comments.VoteCommentService;
import com.vomatt.comments.dto.CreateCommentRequest;
import com.vomatt.entity.Audience;
import com.vomatt.entity.Tag;
import com.vomatt.entity.User;
import com.vomatt.entity.Vote;
import com.vomatt.repository.PostgresRepositoryTest;
import com.vomatt.repository.UserRepository;
import com.vomatt.votes.dto.CreateVoteRequest;
import com.vomatt.votes.dto.VoteRequest;
import com.vomatt.votes.dto.VoteResponse;

@Import({ VoteServiceSlice.class, VoteCommentService.class, CommentMapper.class })
@DisplayName("Unlisted Polls (Postgres)")
class UnlistedPollPostgresTest extends PostgresRepositoryTest {

    @Autowired VoteService voteService;
    @Autowired VoteCommentService commentService;
    @Autowired UserRepository userRepository;

    private final OffsetDateTime now = OffsetDateTime.now();
    private final String word = "zq" + System.nanoTime();
    private User owner;
    private User viewer;
    private Tag tag;
    private Vote unlistedOpen;

    @BeforeEach
    void setUp() {
        owner = persistUser("unl-owner-" + System.nanoTime());
        viewer = persistUser("unl-viewer-" + System.nanoTime());
        tag = new Tag("t" + System.nanoTime() % 1_000_000_000, "slug-" + System.nanoTime(), null, 0);
        em.persist(tag);

        poll("public-open", Audience.PUBLIC, now.minusHours(1), now.plusDays(1));
        poll("public-ended", Audience.PUBLIC, now.minusDays(2), now.minusHours(1));
        unlistedOpen = poll("unlisted-open", Audience.UNLISTED, now.minusHours(1), now.plusDays(1));
        poll("unlisted-ended", Audience.UNLISTED, now.minusDays(2), now.minusHours(1));
        em.flush();
        em.clear();
    }

    private Vote poll(String title, Audience audience, OffsetDateTime start, OffsetDateTime end) {
        Vote vote = persistPoll(owner, start, end, "A", "B");
        vote.setTitle(title + " " + word);
        vote.setAudience(audience);
        vote.addTag(tag);
        return vote;
    }

    private static List<String> titles(List<VoteResponse> items) {
        return items.stream().map(v -> v.getTitle().split(" ")[0]).toList();
    }

    private CreateVoteRequest request(Audience audience) {
        CreateVoteRequest request = new CreateVoteRequest();
        request.setTitle("created " + word);
        request.setOptions(List.of(option("A"), option("B")));
        request.setStartTime(now.plusHours(1));
        request.setEndTime(now.plusDays(1));
        request.setAudience(audience);
        request.setTagIds(List.of(tag.getId()));
        return request;
    }

    private static CreateVoteRequest.VoteOptionRequest option(String text) {
        CreateVoteRequest.VoteOptionRequest option = new CreateVoteRequest.VoteOptionRequest();
        option.setText(text);
        return option;
    }

    private int usageCount() {
        em.flush();
        em.clear();
        return em.find(Tag.class, tag.getId()).getUsageCount();
    }

    @Test
    @DisplayName("should leave Unlisted Polls out of Feed, Explore and Search when listing")
    void shouldHideUnlistedPollsWhenListing() {
        assertThat(titles(voteService.listVotes(VoteListOrder.NEWEST, tag.getSlug(), null, null, 50, null).items()))
                .containsExactly("public-open");
        assertThat(titles(voteService.listVotes(VoteListOrder.CLOSING, tag.getSlug(), null, null, 50, null).items()))
                .containsExactly("public-open");
        assertThat(titles(voteService.listVotes(VoteListOrder.ENDED, tag.getSlug(), null, null, 50, null).items()))
                .containsExactly("public-ended");
        assertThat(titles(voteService.listVotes(VoteListOrder.NEWEST, null, word, null, 50, null).items()))
                .containsExactly("public-open");
    }

    @Test
    @DisplayName("should leave Unlisted Polls out of the profile list and Poll count when viewing a profile")
    void shouldHideUnlistedPollsWhenViewingProfile() {
        assertThat(titles(voteService.getUserPolls(owner.getUsername(), null, 50, null).items()))
                .containsExactly("public-open", "public-ended");
        assertThat(userRepository.findProfileByUsername(owner.getUsername()).orElseThrow().getTotalPolls())
                .isEqualTo(2L);
    }

    @Test
    @DisplayName("should list the owner's Unlisted Polls in My Polls when the owner lists them")
    void shouldListUnlistedPollsWhenOwnerListsMyPolls() {
        String ownerId = owner.getId().toString();
        assertThat(titles(voteService.getMyPolls(ownerId, "open", null, 50).items()))
                .containsExactlyInAnyOrder("public-open", "unlisted-open");
        assertThat(titles(voteService.getMyPolls(ownerId, "ended", null, 50).items()))
                .containsExactlyInAnyOrder("public-ended", "unlisted-ended");
    }

    @Test
    @DisplayName("should let anyone with the link read, vote and Comment when the Poll is Unlisted")
    void shouldAllowLinkHoldersWhenPollUnlisted() {
        String id = unlistedOpen.getId().toString();
        String viewerId = viewer.getId().toString();

        VoteResponse signedOut = voteService.getVote(id, null);
        assertThat(signedOut.getAudience()).isEqualTo(Audience.UNLISTED);

        VoteRequest ballot = new VoteRequest();
        ballot.setOptionIds(List.of(signedOut.getOptions().getFirst().getId()));
        assertThat(voteService.vote(id, ballot, viewerId).getMyOptionId())
                .isEqualTo(signedOut.getOptions().getFirst().getId());

        CreateCommentRequest comment = new CreateCommentRequest();
        comment.setText("hi");
        commentService.createComment(id, viewerId, comment);
        assertThat(commentService.getCommentsByVote(id, null, null, null).items()).hasSize(1);
    }

    @Test
    @DisplayName("should count only Public Polls in popular tag usage when Audience is set or changed")
    void shouldCountOnlyPublicPollsWhenTaggingByAudience() {
        String ownerId = owner.getId().toString();

        String unlistedId = voteService.createVote(request(Audience.UNLISTED), ownerId).getId();
        assertThat(usageCount()).isZero();

        voteService.updateVote(unlistedId, request(Audience.PUBLIC), ownerId);
        assertThat(usageCount()).isEqualTo(1);

        voteService.updateVote(unlistedId, request(Audience.UNLISTED), ownerId);
        assertThat(usageCount()).isZero();

        voteService.deleteVote(unlistedId, ownerId);
        assertThat(usageCount()).isZero();
    }
}
