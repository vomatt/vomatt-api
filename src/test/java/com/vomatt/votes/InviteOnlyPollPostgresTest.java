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
import com.vomatt.comments.dto.CreateCommentRequest;
import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.entity.Audience;
import com.vomatt.entity.Invitee;
import com.vomatt.entity.Tag;
import com.vomatt.entity.User;
import com.vomatt.entity.Vote;
import com.vomatt.repository.NotificationReadRepository;
import com.vomatt.repository.PostgresRepositoryTest;
import com.vomatt.repository.UserRepository;
import com.vomatt.votes.dto.CreateVoteRequest;
import com.vomatt.votes.dto.InviteeResponse;
import com.vomatt.votes.dto.InviteesRequest;
import com.vomatt.votes.dto.VoteRequest;
import com.vomatt.votes.dto.VoteResponse;

@Import({ VoteServiceSlice.class, VoteCommentService.class, CommentMapper.class })
@DisplayName("Invite-only Polls (Postgres)")
class InviteOnlyPollPostgresTest extends PostgresRepositoryTest {

    @Autowired VoteService voteService;
    @Autowired VoteCommentService commentService;
    @Autowired UserRepository userRepository;
    @Autowired NotificationReadRepository notificationReadRepository;

    private final OffsetDateTime now = OffsetDateTime.now();
    private final String word = "zq" + System.nanoTime();
    private User owner;
    private User invitee;
    private User stranger;
    private Tag tag;
    private Vote inviteOpen;

    @BeforeEach
    void setUp() {
        owner = persistUser("inv-owner-" + System.nanoTime());
        invitee = persistUser("inv-guest-" + System.nanoTime());
        stranger = persistUser("inv-other-" + System.nanoTime());
        tag = new Tag("t" + System.nanoTime() % 1_000_000_000, "slug-" + System.nanoTime(), null, 0);
        em.persist(tag);

        poll("public-open", Audience.PUBLIC, now.minusHours(1), now.plusDays(1));
        poll("public-ended", Audience.PUBLIC, now.minusDays(2), now.minusHours(1));
        inviteOpen = poll("invite-open", Audience.INVITE_ONLY, now.minusHours(1), now.plusDays(1));
        Vote inviteEnded = poll("invite-ended", Audience.INVITE_ONLY, now.minusDays(2), now.minusHours(1));
        em.persist(new Invitee(inviteOpen, invitee));
        em.persist(new Invitee(inviteEnded, invitee));
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

    private List<String> myPolls(User user, String status) {
        return titles(voteService.getMyPolls(user.getId().toString(), status, null, 50).items());
    }

    private static void assertNotFound(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.VOTE_NOT_FOUND));
    }

    private VoteRequest ballotFor(String voteId, String userId) {
        VoteRequest ballot = new VoteRequest();
        ballot.setOptionIds(List.of(voteService.getVote(voteId, userId).getOptions().getFirst().getId()));
        return ballot;
    }

    @Test
    @DisplayName("should leave Invite-only Polls out of Feed, Explore, Search and the profile when listing")
    void shouldHideInviteOnlyPollsWhenListing() {
        String viewer = invitee.getId().toString();
        assertThat(titles(voteService.listVotes(VoteListOrder.NEWEST, tag.getSlug(), null, null, 50, viewer).items()))
                .containsExactly("public-open");
        assertThat(titles(voteService.listVotes(VoteListOrder.CLOSING, tag.getSlug(), null, null, 50, viewer).items()))
                .containsExactly("public-open");
        assertThat(titles(voteService.listVotes(VoteListOrder.ENDED, tag.getSlug(), null, null, 50, viewer).items()))
                .containsExactly("public-ended");
        assertThat(titles(voteService.listVotes(VoteListOrder.NEWEST, null, word, null, 50, viewer).items()))
                .containsExactly("public-open");
        assertThat(titles(voteService.getUserPolls(owner.getUsername(), null, 50, viewer).items()))
                .containsExactly("public-open", "public-ended");
        assertThat(userRepository.findProfileByUsername(owner.getUsername()).orElseThrow().getTotalPolls())
                .isEqualTo(2L);
    }

    @Test
    @DisplayName("should list Invite-only Polls in My Polls of the owner and Invitees only when listing My Polls")
    void shouldListInviteOnlyPollsWhenOwnerOrInviteeListsMyPolls() {
        assertThat(myPolls(owner, "open")).containsExactlyInAnyOrder("public-open", "invite-open");
        assertThat(myPolls(owner, "ended")).containsExactlyInAnyOrder("public-ended", "invite-ended");
        assertThat(myPolls(invitee, "open")).containsExactly("invite-open");
        assertThat(myPolls(invitee, "ended")).containsExactly("invite-ended");
        assertThat(myPolls(stranger, "open")).isEmpty();
        assertThat(myPolls(stranger, "ended")).isEmpty();
    }

    @Test
    @DisplayName("should send the Ended Notification to an Invitee without a Ballot when an Invite-only Poll Ended")
    void shouldNotifyInviteeWhenInviteOnlyPollEnded() {
        // Ended Notification of invite-ended plus the Invitation to invite-open
        assertThat(notificationReadRepository.countUnread(invitee.getId(), now)).isEqualTo(2);
        assertThat(notificationReadRepository.countUnread(stranger.getId(), now)).isZero();
        VoteResponse ended = voteService.getMyPolls(invitee.getId().toString(), "ended", null, 50).items().getFirst();
        assertThat(ended.getUnread()).isTrue();
    }

    @Test
    @DisplayName("should let the owner and Invitees read, vote and Comment, and hide the Poll from others, when Invite-only")
    void shouldAllowOnlyOwnerAndInviteesWhenPollInviteOnly() {
        String id = inviteOpen.getId().toString();
        String inviteeId = invitee.getId().toString();
        String strangerId = stranger.getId().toString();

        VoteResponse seen = voteService.getVote(id, inviteeId);
        assertThat(seen.getAudience()).isEqualTo(Audience.INVITE_ONLY);
        assertThat(seen.getInviteeCount()).isEqualTo(1L);
        assertThat(voteService.vote(id, ballotFor(id, inviteeId), inviteeId, null).getParticipantCount()).isEqualTo(1);
        CreateCommentRequest comment = new CreateCommentRequest();
        comment.setText("hi");
        commentService.createComment(id, inviteeId, comment);
        assertThat(commentService.getCommentsByVote(id, null, null, inviteeId).items()).hasSize(1);

        assertNotFound(() -> voteService.getVote(id, null));
        assertNotFound(() -> voteService.getVote(id, strangerId));
        assertNotFound(() -> commentService.getCommentsByVote(id, null, null, strangerId));
        assertNotFound(() -> commentService.createComment(id, strangerId, comment));
    }

    @Test
    @DisplayName("should refuse removing an Invitee holding a Ballot and remove one without, when the owner removes Invitees")
    void shouldRemoveOnlyInviteesWithoutBallotWhenOwnerRemoves() {
        String id = inviteOpen.getId().toString();
        String ownerId = owner.getId().toString();
        String inviteeId = invitee.getId().toString();
        String strangerId = stranger.getId().toString();
        voteService.vote(id, ballotFor(id, inviteeId), inviteeId, null);
        InviteesRequest add = new InviteesRequest();
        add.setUserIds(List.of(stranger.getId(), stranger.getId(), invitee.getId()));

        List<InviteeResponse> invitees = voteService.addInvitees(id, add, ownerId);
        assertThat(invitees).extracting(InviteeResponse::username, InviteeResponse::hasBallot)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(invitee.getUsername(), true),
                        org.assertj.core.groups.Tuple.tuple(stranger.getUsername(), false));
        assertThat(voteService.getVote(id, strangerId).getInviteeCount()).isEqualTo(2L);

        assertThatThrownBy(() -> voteService.removeInvitee(id, inviteeId, ownerId))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.VOTE_INVITEE_HAS_BALLOT));
        assertThat(voteService.removeInvitee(id, strangerId, ownerId)).extracting(InviteeResponse::username)
                .containsExactly(invitee.getUsername());
        assertNotFound(() -> voteService.getVote(id, strangerId));
    }

    @Test
    @DisplayName("should store Invitees once, replace them on edit and drop them when Audience changes, without counting tags")
    void shouldManageInviteesWhenCreatingAndEditing() {
        String ownerId = owner.getId().toString();
        CreateVoteRequest request = new CreateVoteRequest();
        request.setTitle("created " + word);
        request.setOptions(List.of(option("A"), option("B")));
        request.setStartTime(now.plusHours(1));
        request.setEndTime(now.plusDays(1));
        request.setAudience(Audience.INVITE_ONLY);
        request.setTagIds(List.of(tag.getId()));
        request.setInviteeIds(List.of(invitee.getId(), invitee.getId(), owner.getId()));

        String id = voteService.createVote(request, ownerId).getId();
        assertThat(voteService.getVote(id, ownerId).getInviteeCount()).isEqualTo(1L);
        assertThat(usageCount()).isZero();

        request.setInviteeIds(List.of(invitee.getId(), stranger.getId()));
        assertThat(voteService.updateVote(id, request, ownerId).getInviteeCount()).isEqualTo(2L);

        request.setInviteeIds(null);
        request.setAudience(Audience.UNLISTED);
        VoteResponse unlisted = voteService.updateVote(id, request, ownerId);
        assertThat(unlisted.getInviteeCount()).isNull();
        assertThat(voteService.getInvitees(id, ownerId)).isEmpty();
        assertThat(myPolls(invitee, "open")).containsExactly("invite-open");
    }

    @Test
    @DisplayName("should delete Invitee rows with the Poll when the Poll row is deleted")
    void shouldCascadeInviteesWhenPollRowDeleted() {
        em.createNativeQuery("DELETE FROM vomatt.votes WHERE id = :id").setParameter("id", inviteOpen.getId()).executeUpdate();

        Number left = (Number) em.createNativeQuery("SELECT COUNT(*) FROM vomatt.vote_invitees WHERE vote_id = :id")
                .setParameter("id", inviteOpen.getId()).getSingleResult();
        assertThat(left.longValue()).isZero();
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
}
