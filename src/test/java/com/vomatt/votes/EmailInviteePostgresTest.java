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
import com.vomatt.entity.Audience;
import com.vomatt.entity.Invitee;
import com.vomatt.entity.User;
import com.vomatt.entity.Vote;
import com.vomatt.repository.InviteeRepository;
import com.vomatt.repository.NotificationReadRepository;
import com.vomatt.repository.PostgresRepositoryTest;
import com.vomatt.votes.dto.InviteeResponse;
import com.vomatt.votes.dto.InviteesRequest;
import com.vomatt.votes.dto.VoteResponse;

@Import(VoteServiceSlice.class)
@DisplayName("Email Invitees and binding (Postgres)")
class EmailInviteePostgresTest extends PostgresRepositoryTest {

    @Autowired VoteService voteService;
    @Autowired InviteeRepository inviteeRepository;
    @Autowired NotificationReadRepository notificationReadRepository;

    private final OffsetDateTime now = OffsetDateTime.now();
    private final String carolEmail = "carol-" + System.nanoTime() + "@example.com";
    private User owner;
    private Vote poll;

    @BeforeEach
    void setUp() {
        owner = persistUser("em-owner-" + System.nanoTime());
        poll = persistPoll(owner, now.minusHours(1), now.plusDays(1), "A", "B");
        poll.setAudience(Audience.INVITE_ONLY);
        em.flush();
    }

    private List<InviteeResponse> inviteEmail(String email) {
        InviteesRequest request = new InviteesRequest();
        request.setEmails(List.of(email));
        return voteService.addInvitees(poll.getId().toString(), request, owner.getId().toString());
    }

    // Stands in for the find-or-create path in auth once the address is verified
    private User signUpAndBind(String email) {
        User user = User.builder().username("em-carol-" + System.nanoTime()).email(email).build();
        em.persist(user);
        notificationReadRepository.deleteReadsForEmailBinding(user.getId(), email);
        inviteeRepository.bindEmail(user.getId(), email);
        inviteeRepository.deleteDuplicateEmail(user.getId(), email);
        em.clear();
        return user;
    }

    @Test
    @DisplayName("should list and count a pending email Invitee without granting anyone access")
    void shouldListPendingEmailInviteeWhenInvitedByEmail() {
        List<InviteeResponse> invitees = inviteEmail(carolEmail.toUpperCase());
        em.clear();

        assertThat(invitees).singleElement().satisfies(i -> {
            assertThat(i.userId()).isNull();
            assertThat(i.email()).isEqualTo(carolEmail);
            assertThat(i.hasBallot()).isFalse();
        });
        assertThat(voteService.getVote(poll.getId().toString(), owner.getId().toString()).getInviteeCount()).isEqualTo(1L);
        User stranger = persistUser("em-stranger-" + System.nanoTime());
        assertThatThrownBy(() -> voteService.getVote(poll.getId().toString(), stranger.getId().toString()))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("should turn the email Invitee into a user Invitee with access and a My Polls entry once the email is verified")
    void shouldBindEmailInviteeWhenEmailVerified() {
        inviteEmail(carolEmail);

        User carol = signUpAndBind(carolEmail);

        List<InviteeResponse> invitees = voteService.getInvitees(poll.getId().toString(), owner.getId().toString());
        assertThat(invitees).singleElement().satisfies(i -> {
            assertThat(i.userId()).isEqualTo(carol.getId().toString());
            assertThat(i.email()).isNull();
        });
        assertThat(voteService.getVote(poll.getId().toString(), carol.getId().toString()).getInviteeCount()).isEqualTo(1L);
        assertThat(voteService.getMyPolls(carol.getId().toString(), "open", null, null).items())
                .extracting(VoteResponse::getId).containsExactly(poll.getId().toString());
    }

    @Test
    @DisplayName("should give an unbound email no Invitation, Ended Notification, My Polls entry or access; after binding the user gets them")
    void shouldNotifyOnlyAfterBindingWhenInvitedByEmail() {
        inviteEmail(carolEmail);
        // Carol already has an account with that email that was never bound (no verified sign-in since), plus a stale read row
        User carol = User.builder().username("em-carol-" + System.nanoTime()).email(carolEmail).build();
        em.persist(carol);
        notificationReadRepository.markRead(carol.getId(), poll.getId(), now);
        em.flush();
        OffsetDateTime afterEnd = now.plusDays(2);

        // Pending row has user_id NULL: NOTIFIED_POLLS, MY_POLL_IDS and Vote.isVisibleTo must not match it
        assertThat(notificationReadRepository.countUnread(carol.getId(), now)).isZero();
        assertThat(notificationReadRepository.isNotified(carol.getId(), poll.getId(), now)).isFalse();
        assertThat(notificationReadRepository.isNotified(carol.getId(), poll.getId(), afterEnd)).isFalse();
        assertThat(voteService.getMyPolls(carol.getId().toString(), "open", null, null).items()).isEmpty();
        assertThatThrownBy(() -> voteService.getVote(poll.getId().toString(), carol.getId().toString()))
                .isInstanceOf(ApiException.class);

        notificationReadRepository.deleteReadsForEmailBinding(carol.getId(), carolEmail);
        inviteeRepository.bindEmail(carol.getId(), carolEmail);
        inviteeRepository.deleteDuplicateEmail(carol.getId(), carolEmail);
        em.clear();

        // The stale read row is gone, so the Invitation is unread; once the Poll Ends, the Ended Notification follows
        assertThat(notificationReadRepository.countUnread(carol.getId(), now)).isEqualTo(1);
        assertThat(notificationReadRepository.isNotified(carol.getId(), poll.getId(), now)).isTrue();
        assertThat(notificationReadRepository.countUnread(carol.getId(), afterEnd)).isEqualTo(1);
        assertThat(notificationReadRepository.isNotified(carol.getId(), poll.getId(), afterEnd)).isTrue();
        assertThat(voteService.getMyPolls(carol.getId().toString(), "open", null, null).items()).hasSize(1);
    }

    @Test
    @DisplayName("should drop the email row when the verified user already is an Invitee, and bind nothing on a rerun")
    void shouldDropEmailRowWhenUserAlreadyInvitee() {
        User carol = User.builder().username("em-carol-" + System.nanoTime()).email(carolEmail).build();
        em.persist(carol);
        em.persist(new Invitee(poll, carol));
        em.persist(new Invitee(poll, carolEmail));
        em.flush();

        assertThat(inviteeRepository.bindEmail(carol.getId(), carolEmail)).isZero();
        assertThat(inviteeRepository.deleteDuplicateEmail(carol.getId(), carolEmail)).isEqualTo(1);
        assertThat(inviteeRepository.bindEmail(carol.getId(), carolEmail)).isZero();
        em.clear();

        assertThat(inviteeRepository.findByVoteId(poll.getId())).singleElement()
                .satisfies(i -> assertThat(i.getUser().getId()).isEqualTo(carol.getId()));
    }

    @Test
    @DisplayName("should store an email of a registered user as that user")
    void shouldInviteUserWhenEmailRegistered() {
        User carol = User.builder().username("em-carol-" + System.nanoTime()).email(carolEmail).build();
        em.persist(carol);
        em.flush();

        assertThat(inviteEmail(carolEmail)).singleElement()
                .satisfies(i -> assertThat(i.userId()).isEqualTo(carol.getId().toString()));
    }

    @Test
    @DisplayName("should remove a pending email Invitee by its address")
    void shouldRemoveEmailInviteeWhenPending() {
        inviteEmail(carolEmail);

        assertThat(voteService.removeEmailInvitee(poll.getId().toString(), carolEmail, owner.getId().toString())).isEmpty();
    }

    @Test
    @DisplayName("should reject a second row for the same Poll and email")
    void shouldRejectDuplicateWhenSameEmailInvitedTwice() {
        em.persist(new Invitee(poll, carolEmail));
        em.flush();
        em.persist(new Invitee(poll, carolEmail));

        assertThatThrownBy(() -> em.flush()).hasMessageContaining("uk_vote_invitees_vote_email");
    }

    @Test
    @DisplayName("should reject a row with both user and email set")
    void shouldRejectRowWhenUserAndEmailBothSet() {
        em.persist(new Invitee(poll, owner, carolEmail));

        assertThatThrownBy(() -> em.flush()).hasMessageContaining("chk_vote_invitees_user_or_email");
    }
}
