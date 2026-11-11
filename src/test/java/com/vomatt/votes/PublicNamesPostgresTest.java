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
import com.vomatt.comments.dto.CommentDto;
import com.vomatt.comments.dto.CreateCommentRequest;
import com.vomatt.entity.Audience;
import com.vomatt.entity.Invitee;
import com.vomatt.entity.User;
import com.vomatt.entity.UserPreference;
import com.vomatt.entity.Vote;
import com.vomatt.repository.PostgresRepositoryTest;
import com.vomatt.votes.dto.InviteeResponse;
import com.vomatt.votes.dto.VoteResponse;

@Import({ VoteServiceSlice.class, VoteCommentService.class, CommentMapper.class })
@DisplayName("Public names on Polls, Comments and Invitees (Postgres)")
class PublicNamesPostgresTest extends PostgresRepositoryTest {

    @Autowired VoteService voteService;
    @Autowired VoteCommentService commentService;

    private User owner;
    private User guest;
    private String pollId;

    @BeforeEach
    void setUp() {
        // The owner shows their real name; the guest keeps the defaults (display name public, real name hidden)
        owner = named("names-owner-" + System.nanoTime(), "Rubes", "Ruby", "Ho");
        show(owner, "firstName", true);
        show(owner, "lastName", true);
        guest = named("names-guest-" + System.nanoTime(), "Kai", "Kai", "Lin");
        Vote poll = persistPoll(owner, OffsetDateTime.now().minusHours(1), OffsetDateTime.now().plusDays(1),
                Audience.INVITE_ONLY, "A", "B");
        em.persist(new Invitee(poll, guest));
        em.flush();
        em.clear();
        pollId = poll.getId().toString();
    }

    private User named(String username, String displayName, String firstName, String lastName) {
        User user = persistUser(username);
        user.setDisplayName(displayName);
        user.setFirstName(firstName);
        user.setLastName(lastName);
        return user;
    }

    private void show(User user, String field, boolean shown) {
        UserPreference pref = new UserPreference();
        pref.setUser(user);
        pref.setKey("visibility." + field);
        pref.setValue(Boolean.toString(shown));
        em.persist(pref);
    }

    @Test
    @DisplayName("should name a Poll's owner as their visibility settings allow")
    void shouldShowCreatorNamesWhenOwnerShowsThem() {
        VoteResponse poll = voteService.getVote(pollId, guest.getId().toString());

        assertThat(List.of(poll.getCreatorDisplayName(), poll.getCreatorFirstName(), poll.getCreatorLastName()))
                .containsExactly("Rubes", "Ruby", "Ho");
    }

    @Test
    @DisplayName("should name Invitees with their hidden real name left out")
    void shouldMaskInviteeRealNameWhenHiddenByDefault() {
        InviteeResponse invitee = voteService.getInvitees(pollId, owner.getId().toString()).getFirst();

        assertThat(invitee.displayName()).isEqualTo("Kai");
        assertThat(invitee.firstName()).isNull();
        assertThat(invitee.lastName()).isNull();
    }

    @Test
    @DisplayName("should name a Comment's author as their visibility settings allow, when posted and when listed")
    void shouldMaskAuthorNamesWhenCommenting() {
        CreateCommentRequest request = new CreateCommentRequest();
        request.setText("hi");
        String guestId = guest.getId().toString();

        CommentDto posted = commentService.createComment(pollId, guestId, request);
        CommentDto listed = commentService.getCommentsByVote(pollId, null, null, guestId).items().getFirst();

        for (CommentDto comment : List.of(posted, listed)) {
            assertThat(comment.getAuthorDisplayName()).isEqualTo("Kai");
            assertThat(comment.getAuthorFirstName()).isNull();
            assertThat(comment.getAuthorLastName()).isNull();
        }
    }
}
