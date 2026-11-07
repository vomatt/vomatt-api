package com.vomatt.votes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.assertj.core.groups.Tuple;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import com.vomatt.common.exception.ApiException;
import com.vomatt.entity.Audience;
import com.vomatt.entity.Invitee;
import com.vomatt.entity.NotificationKind;
import com.vomatt.entity.User;
import com.vomatt.entity.Vote;
import com.vomatt.entity.VoteStatus;
import com.vomatt.repository.InviteeRepository;
import com.vomatt.repository.NotificationRepository;
import com.vomatt.repository.PollNotifications;
import com.vomatt.repository.PostgresRepositoryTest;
import com.vomatt.votes.dto.CreateVoteRequest;
import com.vomatt.votes.dto.InviteeResponse;
import com.vomatt.votes.dto.InviteesRequest;
import com.vomatt.votes.dto.VoteRequest;
import com.vomatt.votes.dto.VoteResponse;

@Import(VoteServiceSlice.class)
@DisplayName("Email Invitees and binding (Postgres)")
class EmailInviteePostgresTest extends PostgresRepositoryTest {

    @Autowired VoteService voteService;
    @Autowired InviteeRepository inviteeRepository;
    @Autowired NotificationRepository notificationRepository;

    // Postgres keeps microseconds; Linux clocks have nanoseconds, so stored times would not round-trip
    private final OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
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

    private User account(String email) {
        User user = User.builder().username("em-acct-" + System.nanoTime()).email(email).build();
        em.persist(user);
        em.flush();
        return user;
    }

    // What the find-or-create path in auth runs once the address is verified
    private User signUpAndBind(String email) {
        User user = User.builder().username("em-carol-" + System.nanoTime()).email(email).avatarUrl("bot:boba").build();
        em.persist(user);
        voteService.bindEmailInvitees(user);
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
    @DisplayName("should tie the email Invitee to the user once the email is verified, still listing it only by email, also after a rerun")
    void shouldBindEmailInviteeWhenEmailVerified() {
        inviteEmail(carolEmail);

        User carol = signUpAndBind(carolEmail);
        voteService.bindEmailInvitees(carol);
        em.clear();

        assertThat(inviteeRepository.findByVoteId(poll.getId())).singleElement().satisfies(i -> {
            assertThat(i.getUser().getId()).isEqualTo(carol.getId());
            assertThat(i.getEmail()).isEqualTo(carolEmail);
        });
        assertEmailOnly(voteService.getInvitees(poll.getId().toString(), owner.getId().toString()));
        assertThat(voteService.getVote(poll.getId().toString(), carol.getId().toString()).getInviteeCount()).isEqualTo(1L);
        assertThat(voteService.getMyPolls(carol.getId().toString(), "open", null, null).items())
                .extracting(VoteResponse::getId).containsExactly(poll.getId().toString());
    }

    @Test
    @DisplayName("should store a Gmail variant in canonical form and bind it to the user who verifies another variant")
    void shouldBindEmailInviteeWhenGmailVariantVerified() {
        String local = "carol" + System.nanoTime();
        List<InviteeResponse> invitees = inviteEmail("Ca.rol" + local.substring(5) + "+polls@googlemail.com");

        User carol = signUpAndBind(User.normalizeEmail("c.a.r.o.l" + local.substring(5) + "@gmail.com"));

        assertThat(invitees).singleElement().satisfies(i -> assertThat(i.email()).isEqualTo(local + "@gmail.com"));
        assertThat(inviteeRepository.findByVoteId(poll.getId())).singleElement()
                .satisfies(i -> assertThat(i.getUser().getId()).isEqualTo(carol.getId()));
    }

    @Test
    @DisplayName("should give an unbound email no Invitation, Ended Notification, My Polls entry or access; after binding the user gets them")
    void shouldNotifyOnlyAfterBindingWhenInvitedByEmail() {
        inviteEmail(carolEmail);
        // Carol already has an account with that email that was never bound (no verified sign-in since). She was once
        // invited by ID and read that Invitation before the owner removed her
        User carol = User.builder().username("em-carol-" + System.nanoTime()).email(carolEmail).build();
        em.persist(carol);
        Invitee removed = new Invitee(poll, carol);
        em.persist(removed);
        em.flush();
        reads().markRead(carol.getId(), poll.getId(), now);
        em.remove(removed);
        addTurnout(poll, 1);
        OffsetDateTime afterEnd = now.plusDays(2);

        // Pending row has user_id NULL: the notification branches, MY_POLL_IDS and Vote.isVisibleTo must not match it
        assertThat(reads().countUnread(carol.getId(), now)).isZero();
        assertThat(reads().isNotified(carol.getId(), poll.getId(), now)).isFalse();
        assertThat(reads().isNotified(carol.getId(), poll.getId(), afterEnd)).isFalse();
        assertThat(voteService.getMyPolls(carol.getId().toString(), "open", null, null).items()).isEmpty();
        assertThatThrownBy(() -> voteService.getVote(poll.getId().toString(), carol.getId().toString()))
                .isInstanceOf(ApiException.class);

        voteService.bindEmailInvitees(carol);
        em.clear();

        // The bound row is a new Invitation, unread whatever was read before; once the Poll Ends, the Ended Notification follows
        assertThat(reads().countUnread(carol.getId(), now)).isEqualTo(1);
        assertThat(reads().isNotified(carol.getId(), poll.getId(), now)).isTrue();
        assertThat(reads().countUnread(carol.getId(), afterEnd)).isEqualTo(1);
        assertThat(reads().isNotified(carol.getId(), poll.getId(), afterEnd)).isTrue();
        assertThat(voteService.getMyPolls(carol.getId().toString(), "open", null, null).items()).hasSize(1);
    }

    @Test
    @DisplayName("should bind, not drop, the email row when the verified user already is an Invitee by ID, so the address stays listed")
    void shouldKeepEmailRowWhenUserAlreadyInviteeById() {
        User carol = User.builder().username("em-carol-" + System.nanoTime()).email(carolEmail).build();
        em.persist(carol);
        em.persist(new Invitee(poll, carol));
        em.persist(new Invitee(poll, carolEmail));
        em.flush();

        voteService.bindEmailInvitees(carol);
        voteService.bindEmailInvitees(carol);
        em.clear();

        assertThat(inviteeRepository.findByVoteId(poll.getId()))
                .allSatisfy(i -> assertThat(i.getUser().getId()).isEqualTo(carol.getId()))
                .extracting(Invitee::getEmail).containsExactlyInAnyOrder(null, carolEmail);
        assertThat(voteService.getInvitees(poll.getId().toString(), owner.getId().toString()))
                .extracting(InviteeResponse::email).containsExactly(null, carolEmail);
    }

    @Test
    @DisplayName("should list an address of a user already invited by ID exactly like an unknown address")
    void shouldListEmailWhenAddressBelongsToUserInvitedById() {
        User carol = account(carolEmail);
        InviteesRequest byId = new InviteesRequest();
        byId.setUserIds(List.of(carol.getId()));
        voteService.addInvitees(poll.getId().toString(), byId, owner.getId().toString());
        String unknown = "nobody-" + System.nanoTime() + "@example.com";

        InviteesRequest byEmail = new InviteesRequest();
        byEmail.setEmails(List.of(carolEmail, unknown));
        List<InviteeResponse> invitees = voteService.addInvitees(poll.getId().toString(), byEmail, owner.getId().toString());
        em.clear();

        assertThat(invitees).extracting(InviteeResponse::userId, InviteeResponse::email).containsExactlyInAnyOrder(
                Tuple.tuple(carol.getId().toString(), null), Tuple.tuple(null, carolEmail), Tuple.tuple(null, unknown));
        assertThat(invitees).filteredOn(i -> i.email() != null)
                .allSatisfy(i -> {
                    assertThat(i.userId()).isNull();
                    assertThat(i.username()).isNull();
                    assertThat(i.avatarUrl()).isNull();
                    assertThat(i.hasBallot()).isFalse();
                });
        assertThat(voteService.getVote(poll.getId().toString(), owner.getId().toString()).getInviteeCount()).isEqualTo(3L);
        assertThat(inviteeRepository.findInvitationsSent(poll.getId(), List.of(carolEmail, unknown)))
                .containsExactlyInAnyOrder(carolEmail, unknown);
    }

    @Test
    @DisplayName("should add a user by ID when an address of theirs is already invited, and remove each entry on its own")
    void shouldAddIdInviteeWhenUserAlreadyInvitedByEmail() {
        User carol = account(carolEmail);
        inviteEmail(carolEmail);

        InviteesRequest byId = new InviteesRequest();
        byId.setUserIds(List.of(carol.getId()));
        assertThat(voteService.addInvitees(poll.getId().toString(), byId, owner.getId().toString()))
                .extracting(InviteeResponse::userId, InviteeResponse::email)
                .containsExactly(Tuple.tuple(null, carolEmail), Tuple.tuple(carol.getId().toString(), null));

        assertThat(voteService.removeInvitee(poll.getId().toString(), carol.getId().toString(), owner.getId().toString()))
                .extracting(InviteeResponse::email).containsExactly(carolEmail);
        assertThat(voteService.addInvitees(poll.getId().toString(), byId, owner.getId().toString())).hasSize(2);
        assertThat(voteService.removeEmailInvitee(poll.getId().toString(), carolEmail, owner.getId().toString()))
                .extracting(InviteeResponse::userId).containsExactly(carol.getId().toString());
    }

    @Test
    @DisplayName("should show a voted user's Ballot on their first entry only, so a later address of theirs reads, removes and blocks auto-end like an unknown one")
    void shouldShowBallotOnFirstEntryOnlyWhenUserListedTwice() {
        // The pending unknown address keeps the Poll Open past bob's Ballot
        String unknown = "nobody-" + System.nanoTime() + "@example.com";
        User bob = votedInvitee(unknown);

        InviteesRequest byEmail = new InviteesRequest();
        byEmail.setEmails(List.of(bob.getEmail()));
        List<InviteeResponse> invitees = voteService.addInvitees(poll.getId().toString(), byEmail, owner.getId().toString());

        assertThat(invitees).extracting(InviteeResponse::email, InviteeResponse::hasBallot).containsExactlyInAnyOrder(
                Tuple.tuple(null, true), Tuple.tuple(bob.getEmail(), false), Tuple.tuple(unknown, false));
        voteService.removeEmailInvitee(poll.getId().toString(), unknown, owner.getId().toString());
        assertThat(reloaded().getStatus()).as("bob's address blocks the end like a pending one").isEqualTo(VoteStatus.OPEN);
        assertThat(voteService.removeEmailInvitee(poll.getId().toString(), bob.getEmail(), owner.getId().toString()))
                .singleElement().satisfies(i -> assertThat(i.hasBallot()).isTrue());
        assertThat(reloaded().getStatus()).isEqualTo(VoteStatus.ENDED);
    }

    @Test
    @DisplayName("should give a user invited by ID and by address one Invitation, and one Cancelled notification once the Poll is Cancelled")
    void shouldNotifyOncePerPollWhenUserListedTwice() {
        User carol = account(carolEmail);
        em.persist(new Invitee(poll, carol));
        em.persist(new Invitee(poll, carol, carolEmail));
        Vote cancelled = persistPoll(owner, now.plusDays(1), now.minusHours(1), Audience.INVITE_ONLY, "A", "B");
        em.persist(new Invitee(cancelled, carol));
        em.persist(new Invitee(cancelled, carol, carolEmail));
        em.flush();

        List<NotificationRepository.NotificationRow> rows = notificationRepository.findPage(carol.getId(),
                Set.of(NotificationKind.INVITATION, NotificationKind.CANCELLED), null, now, 50);

        assertThat(rows).extracting(NotificationRepository.NotificationRow::kind, NotificationRepository.NotificationRow::voteId)
                .containsExactlyInAnyOrder(Tuple.tuple(NotificationKind.INVITATION, poll.getId()),
                        Tuple.tuple(NotificationKind.CANCELLED, cancelled.getId()));
        // The Invitation comes from the first row, so a user invited once keeps the read state they had
        UUID first = inviteeRepository.findByVoteIdAndUserIdAndEmailIsNull(poll.getId(), carol.getId()).orElseThrow().getId();
        assertThat(rows).filteredOn(r -> r.kind() == NotificationKind.INVITATION).singleElement()
                .satisfies(r -> assertThat(r.sourceId()).isEqualTo(first));
    }

    @Test
    @DisplayName("should let an ID row and an email row of one user coexist, but reject a second ID row")
    void shouldKeyInviteesByKindWhenSameUserInvitedTwice() {
        User carol = account(carolEmail);
        em.persist(new Invitee(poll, carol));
        em.persist(new Invitee(poll, carol, carolEmail));
        em.flush();
        em.persist(new Invitee(poll, carol));

        assertThatThrownBy(() -> em.flush()).hasMessageContaining("uk_vote_invitees_vote_user");
    }

    @Test
    @DisplayName("should list a registered user's email only by the address, while that user gets access right away")
    void shouldShowEmailOnlyWhenEmailRegistered() {
        User carol = account(carolEmail);

        assertEmailOnly(inviteEmail(carolEmail));
        em.clear();
        assertEmailOnly(voteService.getInvitees(poll.getId().toString(), owner.getId().toString()));
        assertThat(voteService.getVote(poll.getId().toString(), carol.getId().toString()).getInviteeCount()).isEqualTo(1L);
        assertThat(reads().isNotified(carol.getId(), poll.getId(), now)).isTrue();
    }

    @Test
    @DisplayName("should keep a suspended user's email as a pending email Invitee, listed like any address, without access")
    void shouldKeepEmailPendingWhenUserSuspended() {
        User gone = account(carolEmail);
        gone.setActive(false);
        em.flush();

        assertEmailOnly(inviteEmail(carolEmail));
        assertThat(inviteeRepository.findByVoteId(poll.getId())).singleElement()
                .satisfies(i -> assertThat(i.getUser()).isNull());
    }

    @Test
    @DisplayName("should remove a pending email Invitee by its address")
    void shouldRemoveEmailInviteeWhenPending() {
        inviteEmail(carolEmail);

        assertThat(voteService.removeEmailInvitee(poll.getId().toString(), carolEmail, owner.getId().toString())).isEmpty();
    }

    @Test
    @DisplayName("should remove an email Invitee tied to a user by its address, but not by that user's ID")
    void shouldRemoveOnlyByAddressWhenEmailInviteeTiedToUser() {
        User carol = account(carolEmail);
        inviteEmail(carolEmail);

        assertEmailOnly(voteService.removeInvitee(poll.getId().toString(), carol.getId().toString(), owner.getId().toString()));
        assertThat(voteService.removeEmailInvitee(poll.getId().toString(), carolEmail, owner.getId().toString())).isEmpty();
        em.clear();
        assertThatThrownBy(() -> voteService.getVote(poll.getId().toString(), carol.getId().toString()))
                .isInstanceOf(ApiException.class);
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
    @DisplayName("should reject a row with neither user nor email set")
    void shouldRejectRowWhenNeitherUserNorEmailSet() {
        em.persist(new Invitee(poll, null, null));

        assertThatThrownBy(() -> em.flush()).hasMessageContaining("chk_vote_invitees_user_or_email");
    }

    @Test
    @DisplayName("should keep a removed pending email's sent record, so re-adding it sends no second invitation")
    void shouldKeepSentRecordWhenEmailRemovedAndReAdded() {
        inviteEmail(carolEmail);
        voteService.removeEmailInvitee(poll.getId().toString(), carolEmail, owner.getId().toString());
        em.flush();

        assertThat(inviteeRepository.findInvitationsSent(poll.getId(), List.of(carolEmail))).containsExactly(carolEmail);
        assertThat(inviteEmail(carolEmail)).singleElement().satisfies(i -> assertThat(i.email()).isEqualTo(carolEmail));
    }

    @Test
    @DisplayName("should record every address of one invite as sent in one batch, and no other address")
    void shouldRecordEveryAddressWhenInvitingSeveralEmails() {
        String daveEmail = "dave-" + System.nanoTime() + "@example.com";
        InviteesRequest request = new InviteesRequest();
        request.setEmails(List.of(carolEmail, daveEmail));
        voteService.addInvitees(poll.getId().toString(), request, owner.getId().toString());
        em.flush();

        assertThat(inviteeRepository.findInvitationsSent(poll.getId(), List.of(carolEmail, daveEmail, "x@example.com")))
                .containsExactlyInAnyOrder(carolEmail, daveEmail);
    }

    @Test
    @DisplayName("should replace an Invitee by ID with an email entry on edit when only their account's address is listed, like any address")
    void shouldReplaceIdInviteeWithEmailWhenOnlyAddressListedOnEdit() {
        User carol = User.builder().username("em-carol-" + System.nanoTime()).email(carolEmail).build();
        em.persist(carol);
        Vote scheduled = persistPoll(owner, now.plusDays(1), now.plusDays(2), "A", "B");
        scheduled.setAudience(Audience.INVITE_ONLY);
        em.persist(new Invitee(scheduled, carol));
        em.flush();
        reads().markRead(carol.getId(), scheduled.getId(), now);
        UUID before = inviteeRepository.findByVoteId(scheduled.getId()).getFirst().getId();
        em.clear();

        CreateVoteRequest edit = new CreateVoteRequest();
        edit.setTitle("Edited");
        edit.setOptions(List.of(option("A"), option("B")));
        edit.setStartTime(now.plusDays(1));
        edit.setEndTime(now.plusDays(2));
        edit.setInviteeIds(List.of());
        edit.setInviteeEmails(List.of(carolEmail));
        voteService.updateVote(scheduled.getId().toString(), edit, owner.getId().toString());
        em.flush();
        em.clear();

        // The ID entry goes and the address is a new entry, exactly as for an address with no account
        assertThat(inviteeRepository.findByVoteId(scheduled.getId())).singleElement().satisfies(i -> {
            assertThat(i.getId()).isNotEqualTo(before);
            assertThat(i.getEmail()).isEqualTo(carolEmail);
        });
    }

    @Test
    @DisplayName("should keep the email rows listed in inviteeEmails on edit, matched by address even when tied to a user, and drop the rest")
    void shouldKeepEmailInviteesByAddressWhenEditing() {
        User carol = account(carolEmail);
        String daveEmail = "dave-" + System.nanoTime() + "@example.com";
        Vote scheduled = persistPoll(owner, now.plusDays(1), now.plusDays(2), "A", "B");
        scheduled.setAudience(Audience.INVITE_ONLY);
        em.persist(new Invitee(scheduled, carol, carolEmail));
        em.persist(new Invitee(scheduled, daveEmail));
        em.flush();
        reads().markRead(carol.getId(), scheduled.getId(), now);
        UUID before = inviteeRepository.findByVoteIdAndEmail(scheduled.getId(), carolEmail).orElseThrow().getId();
        em.clear();

        CreateVoteRequest edit = new CreateVoteRequest();
        edit.setTitle("Edited");
        edit.setOptions(List.of(option("A"), option("B")));
        edit.setStartTime(now.plusDays(1));
        edit.setEndTime(now.plusDays(2));
        edit.setInviteeEmails(List.of(carolEmail));
        voteService.updateVote(scheduled.getId().toString(), edit, owner.getId().toString());
        em.flush();
        em.clear();

        assertThat(inviteeRepository.findByVoteId(scheduled.getId())).singleElement()
                .satisfies(i -> assertThat(i.getId()).isEqualTo(before));
        assertThat(reads().countUnread(carol.getId(), now)).isZero();

        edit.setInviteeEmails(List.of());
        voteService.updateVote(scheduled.getId().toString(), edit, owner.getId().toString());
        em.flush();
        assertThat(inviteeRepository.findByVoteId(scheduled.getId())).isEmpty();
    }

    // ─── Auto-end ─────────────────────────────────────────────────────────────

    // An Invitee added by ID who already holds a Ballot; the given pending email rows are added before the Ballot
    private User votedInvitee(String... pendingEmails) {
        User bob = persistUser("em-bob-" + System.nanoTime());
        em.persist(new Invitee(poll, bob));
        for (String email : pendingEmails) {
            em.persist(new Invitee(poll, email));
        }
        em.flush();
        castAs(bob);
        return bob;
    }

    private void castAs(User user) {
        VoteRequest ballot = new VoteRequest();
        ballot.setOptionIds(List.of(voteService.getVote(poll.getId().toString(), user.getId().toString())
                .getOptions().getFirst().getId()));
        voteService.vote(poll.getId().toString(), ballot, user.getId().toString());
    }

    private Vote reloaded() {
        em.flush();
        em.clear();
        return em.find(Vote.class, poll.getId());
    }

    @Test
    @DisplayName("should keep the Poll Open while an email Invitee is pending, and End it when that email is removed")
    void shouldEndPollWhenPendingEmailInviteeRemoved() {
        inviteEmail(carolEmail);
        votedInvitee();
        assertThat(reloaded().getStatus()).as("pending email blocks the end").isEqualTo(VoteStatus.OPEN);

        voteService.removeEmailInvitee(poll.getId().toString(), carolEmail, owner.getId().toString());

        Vote ended = reloaded();
        assertThat(ended.getStatus()).isEqualTo(VoteStatus.ENDED);
        assertThat(ended.getDeadline()).isEqualTo(poll.getDeadline());
    }

    @Test
    @DisplayName("should End the Poll once the bound email Invitee casts the last Ballot")
    void shouldEndPollWhenBoundEmailInviteeVotes() {
        inviteEmail(carolEmail);
        votedInvitee();
        User carol = signUpAndBind(carolEmail);
        assertThat(reloaded().getStatus()).as("binding casts no Ballot").isEqualTo(VoteStatus.OPEN);

        castAs(carol);

        assertThat(reloaded().getStatus()).isEqualTo(VoteStatus.ENDED);
    }

    @Test
    @DisplayName("should keep the Poll Open and the address listed when binding ties it to a voted Invitee added by ID")
    void shouldKeepPendingRowWhenBindingToVotedIdInvitee() {
        String bobEmail = "bob-" + System.nanoTime() + "@example.com";
        User bob = votedInvitee(bobEmail);
        // bob verifies an address that was also invited by email
        bob.setEmail(bobEmail);
        em.merge(bob);
        assertThat(reloaded().getStatus()).isEqualTo(VoteStatus.OPEN);

        voteService.bindEmailInvitees(em.find(User.class, bob.getId()));

        // The bound address reads like any address nobody answered from: still listed, no Ballot, the Poll stays Open
        assertThat(reloaded().getStatus()).isEqualTo(VoteStatus.OPEN);
        assertThat(voteService.getInvitees(poll.getId().toString(), owner.getId().toString()))
                .extracting(InviteeResponse::email, InviteeResponse::hasBallot)
                .containsExactly(Tuple.tuple(null, true), Tuple.tuple(bobEmail, false));
    }

    @Test
    @DisplayName("should End the Poll when binding drops the owner's own pending address and the rest hold a Ballot")
    void shouldEndPollWhenBindingDropsOwnersOwnAddress() {
        votedInvitee(owner.getEmail());
        assertThat(reloaded().getStatus()).isEqualTo(VoteStatus.OPEN);

        voteService.bindEmailInvitees(em.find(User.class, owner.getId()));

        assertThat(reloaded().getStatus()).isEqualTo(VoteStatus.ENDED);
    }

    private static void assertEmailOnly(List<InviteeResponse> invitees) {
        assertThat(invitees).singleElement().satisfies(i -> {
            assertThat(i.email()).isNotNull();
            assertThat(i.userId()).isNull();
            assertThat(i.username()).isNull();
            assertThat(i.avatarUrl()).isNull();
        });
    }

    private static CreateVoteRequest.VoteOptionRequest option(String text) {
        CreateVoteRequest.VoteOptionRequest option = new CreateVoteRequest.VoteOptionRequest();
        option.setText(text);
        return option;
    }

    private PollNotifications reads() {
        return new PollNotifications(notificationRepository);
    }
}
