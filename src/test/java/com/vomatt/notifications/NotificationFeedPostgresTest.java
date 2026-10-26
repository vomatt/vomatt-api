package com.vomatt.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.response.CursorResponse;
import com.vomatt.common.security.RefreshTokenService;
import com.vomatt.entity.Audience;
import com.vomatt.entity.Invitee;
import com.vomatt.entity.NotificationKind;
import com.vomatt.entity.User;
import com.vomatt.entity.UserBlock;
import com.vomatt.entity.UserFollow;
import com.vomatt.entity.UserVote;
import com.vomatt.entity.Vote;
import com.vomatt.entity.VoteComment;
import com.vomatt.entity.VoteStatus;
import com.vomatt.notifications.dto.NotificationResponse;
import com.vomatt.notifications.dto.UpdateNotificationPreferencesRequest;
import com.vomatt.repository.PostgresRepositoryTest;
import com.vomatt.users.UserMapper;
import com.vomatt.users.UserService;
import com.vomatt.votes.VoteServiceSlice;

/** The notification feed, derived on read (ADR 0005): each kind, blocks, preferences, read state and pagination. */
@Import({ NotificationService.class, VoteServiceSlice.class, UserService.class, UserMapper.class })
@DisplayName("Notification feed (Postgres)")
class NotificationFeedPostgresTest extends PostgresRepositoryTest {

    @MockitoBean RefreshTokenService refreshTokenService;
    @Autowired NotificationService notificationService;

    private final OffsetDateTime now = OffsetDateTime.now();
    private User me;
    private User alice;
    private User bob;

    @BeforeEach
    void setUp() {
        me = persistUser("f-me-" + System.nanoTime());
        alice = persistUser("f-alice-" + System.nanoTime());
        bob = persistUser("f-bob-" + System.nanoTime());
    }

    private List<NotificationResponse> feed(User user) {
        em.flush();
        return notificationService.list(user.getId().toString(), null, 50).items();
    }

    private List<NotificationKind> kinds(User user) {
        return feed(user).stream().map(NotificationResponse::kind).toList();
    }

    private long unread(User user) {
        em.flush();
        return notificationService.countUnread(user.getId().toString()).count();
    }

    private void preferences(User user, Boolean resultsIn, Boolean invitations, Boolean comments, Boolean closingSoon) {
        notificationService.updatePreferences(user.getId().toString(),
                new UpdateNotificationPreferencesRequest(resultsIn, invitations, comments, closingSoon));
    }

    private Vote poll(User owner, Audience audience, OffsetDateTime start, OffsetDateTime end) {
        Vote poll = persistPoll(owner, start, end, "A", "B");
        poll.setAudience(audience);
        return poll;
    }

    private Vote invitePoll(User owner, OffsetDateTime start, OffsetDateTime end, User invitee) {
        Vote poll = poll(owner, Audience.INVITE_ONLY, start, end);
        em.persist(new Invitee(poll, invitee));
        return poll;
    }

    private void ballot(User user, Vote poll) {
        em.persist(new UserVote(user, poll, poll.getOptions().iterator().next()));
    }

    private VoteComment comment(User author, Vote poll, VoteComment parent, String text) {
        VoteComment comment = new VoteComment(poll, author, text);
        comment.setParent(parent);
        em.persist(comment);
        return comment;
    }

    private UserFollow follow(User follower, User followed) {
        UserFollow follow = new UserFollow(follower, followed);
        em.persist(follow);
        return follow;
    }

    @Test
    @DisplayName("should list a RESULT with the Poll and no actor when a Poll the user voted in Ended")
    void shouldListResultWhenVotedPollEnded() {
        Vote ended = poll(alice, Audience.PUBLIC, now.minusDays(2), now.minusHours(1));
        ballot(me, ended);

        assertThat(feed(me)).singleElement().satisfies(n -> {
            assertThat(n.kind()).isEqualTo(NotificationKind.RESULT);
            assertThat(n.id()).isEqualTo("result_" + ended.getId());
            assertThat(n.actor()).isNull();
            assertThat(n.read()).isFalse();
            assertThat(n.poll().id()).isEqualTo(ended.getId().toString());
            assertThat(n.poll().status()).isEqualTo(VoteStatus.ENDED);
            assertThat(n.poll().turnout()).isZero();
            assertThat(n.createdAt().toInstant()).isEqualTo(ended.getEndTime().toInstant());
        });
    }

    @Test
    @DisplayName("should list CANCELLED to an Invitee, not the owner, when an Invite-only Poll was Cancelled before it opened")
    void shouldListCancelledWhenInviteOnlyPollCancelled() {
        Vote cancelled = invitePoll(alice, now.plusDays(1), now.plusDays(2), me);
        cancelled.setEndTime(now.minusMinutes(1)); // Cancelled before ADR 0004: ends before its start

        assertThat(feed(me)).singleElement().satisfies(n -> {
            assertThat(n.kind()).isEqualTo(NotificationKind.CANCELLED);
            assertThat(n.actor().username()).isEqualTo(alice.getUsername());
        });
        assertThat(feed(alice)).isEmpty();
    }

    @Test
    @DisplayName("should list an INVITATION from the owner while the Poll is Open, then the RESULT instead once it Ended")
    void shouldReplaceInvitationWithResultWhenPollEnded() {
        Vote open = invitePoll(alice, now.minusHours(1), now.plusDays(2), me);
        List<NotificationResponse> before = feed(me);
        assertThat(before).extracting(NotificationResponse::kind).containsExactly(NotificationKind.INVITATION);
        assertThat(before.getFirst().actor().username()).isEqualTo(alice.getUsername());
        assertThat(before.getFirst().poll().status()).isEqualTo(VoteStatus.OPEN);

        open.closeAt(OffsetDateTime.now());

        assertThat(kinds(me)).containsExactly(NotificationKind.RESULT);
    }

    @Test
    @DisplayName("should list FOLLOWED with the follower as actor, and drop it when the follow is undone")
    void shouldListFollowedWhenSomeoneFollows() {
        UserFollow follow = follow(alice, me);
        follow(me, bob); // following someone notifies them, not the follower

        assertThat(feed(me)).singleElement().satisfies(n -> {
            assertThat(n.kind()).isEqualTo(NotificationKind.FOLLOWED);
            assertThat(n.id()).isEqualTo("followed_" + follow.getId());
            assertThat(n.actor().username()).isEqualTo(alice.getUsername());
            assertThat(n.poll()).isNull();
        });

        em.remove(follow);

        assertThat(feed(me)).isEmpty();
    }

    @Test
    @DisplayName("should list COMMENT for others' Comments on the user's Poll and Replies to the user's Comments, not the user's own or deleted ones")
    void shouldListCommentWhenOthersCommentOrReply() {
        Vote mine = poll(me, Audience.PUBLIC, now.minusHours(2), now.plusDays(1));
        Vote alices = poll(alice, Audience.PUBLIC, now.minusHours(2), now.plusDays(1));
        VoteComment onMine = comment(alice, mine, null, "Ramen,\n\n  obviously");
        comment(me, mine, null, "my own comment");
        VoteComment myComment = comment(me, alices, null, "I disagree");
        VoteComment reply = comment(bob, alices, myComment, "Why?");
        comment(bob, alices, null, "unrelated thread on someone else's Poll");
        comment(bob, mine, null, "deleted").softDelete();

        List<NotificationResponse> feed = feed(me);

        assertThat(feed).extracting(NotificationResponse::id)
                .containsExactlyInAnyOrder("comment_" + onMine.getId(), "comment_" + reply.getId());
        assertThat(feed).filteredOn(n -> n.id().equals("comment_" + onMine.getId())).singleElement().satisfies(n -> {
            assertThat(n.actor().username()).isEqualTo(alice.getUsername());
            assertThat(n.poll().id()).isEqualTo(mine.getId().toString());
            assertThat(n.comment().excerpt()).isEqualTo("Ramen, obviously");
        });
    }

    @Test
    @DisplayName("should not list a Reply on an Invite-only Poll the user is no longer invited to")
    void shouldNotListCommentWhenPollNoLongerVisible() {
        Vote inviteOnly = poll(alice, Audience.INVITE_ONLY, now.minusHours(2), now.plusDays(1));
        VoteComment mine = comment(me, inviteOnly, null, "I was invited once");
        comment(bob, inviteOnly, mine, "reply");

        assertThat(feed(me)).isEmpty();
    }

    @Test
    @DisplayName("should list CLOSING_SOON, once switched on, for an invited or followed user's Poll ending within a day that the user has not voted in")
    void shouldListClosingSoonWhenSwitchedOn() {
        Vote invited = invitePoll(alice, now.minusDays(2), now.plusHours(6), me);
        Vote followed = poll(alice, Audience.PUBLIC, now.minusDays(2), now.plusHours(6));
        follow(me, alice);
        Vote voted = poll(alice, Audience.PUBLIC, now.minusDays(2), now.plusHours(6));
        ballot(me, voted);
        poll(alice, Audience.PUBLIC, now.minusDays(2), now.plusDays(2)); // ends later than a day
        poll(bob, Audience.PUBLIC, now.minusDays(2), now.plusHours(6)); // bob is not followed
        poll(alice, Audience.UNLISTED, now.minusDays(2), now.plusHours(6)); // not Public
        assertThat(kinds(me)).doesNotContain(NotificationKind.CLOSING_SOON); // off by default

        preferences(me, null, null, null, true);

        List<NotificationResponse> closing = feed(me).stream()
                .filter(n -> n.kind() == NotificationKind.CLOSING_SOON).toList();
        assertThat(closing).extracting(n -> n.poll().id())
                .containsExactlyInAnyOrder(invited.getId().toString(), followed.getId().toString());
        assertThat(closing).allSatisfy(n -> {
            assertThat(n.actor().username()).isEqualTo(alice.getUsername());
            assertThat(n.createdAt().toInstant()).isEqualTo(n.poll().endTime().minusHours(24).toInstant());
        });
    }

    @Test
    @DisplayName("should leave out notifications from users the user blocked, users who blocked the user and suspended users")
    void shouldNotListNotificationsWhenActorBlockedOrSuspended() {
        User carol = persistUser("f-carol-" + System.nanoTime());
        Vote mine = poll(me, Audience.PUBLIC, now.minusHours(2), now.plusDays(1));
        follow(alice, me);
        comment(bob, mine, null, "hi");
        follow(carol, me);
        em.persist(new UserBlock(me, alice));  // I blocked alice
        em.persist(new UserBlock(bob, me));    // bob blocked me
        carol.setActive(false);

        assertThat(feed(me)).isEmpty();
        assertThat(unread(me)).isZero();
    }

    @Test
    @DisplayName("should leave switched-off kinds out of the feed and the unread count, and keep FOLLOWED")
    void shouldFilterKindsWhenSwitchedOff() {
        Vote mine = poll(me, Audience.PUBLIC, now.minusDays(2), now.minusHours(1));
        comment(alice, mine, null, "hi");
        follow(alice, me);
        invitePoll(bob, now.minusHours(1), now.plusDays(2), me);
        assertThat(unread(me)).isEqualTo(4);

        preferences(me, false, false, false, null);

        assertThat(kinds(me)).containsExactly(NotificationKind.FOLLOWED);
        assertThat(unread(me)).isEqualTo(1);
        assertThat(notificationService.getPreferences(me.getId().toString()))
                .extracting("resultsIn", "invitations", "comments", "closingSoon").containsExactly(false, false, false, false);
    }

    @Test
    @DisplayName("should mark one notification read by its id, idempotently, and 404 an id the user does not have")
    void shouldMarkReadWhenGivenNotificationId() {
        follow(alice, me);
        follow(bob, me);
        String id = feed(me).getFirst().id();

        notificationService.markRead(me.getId().toString(), id);
        notificationService.markRead(me.getId().toString(), id);

        assertThat(feed(me)).filteredOn(NotificationResponse::read).extracting(NotificationResponse::id).containsExactly(id);
        assertThat(unread(me)).isEqualTo(1);
        assertThatThrownBy(() -> notificationService.markRead(alice.getId().toString(), id))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.NOTIFICATION_NOT_FOUND));
        assertThatThrownBy(() -> notificationService.markRead(me.getId().toString(), "followed_not-a-uuid"))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.NOTIFICATION_NOT_FOUND));
    }

    @Test
    @DisplayName("should read the Poll's RESULT in the feed when the deprecated Poll id is marked read")
    void shouldMarkResultReadWhenGivenDeprecatedPollId() {
        Vote ended = poll(alice, Audience.PUBLIC, now.minusDays(2), now.minusHours(1));
        ballot(me, ended);
        em.flush();

        notificationService.markRead(me.getId().toString(), ended.getId().toString());

        assertThat(feed(me)).singleElement().satisfies(n -> assertThat(n.read()).isTrue());
    }

    @Test
    @DisplayName("should mark everything up to now read on read-all, and leave later notifications unread")
    void shouldMarkEverythingReadWhenReadAll() {
        follow(alice, me);
        ballot(me, poll(bob, Audience.PUBLIC, now.minusDays(2), now.minusHours(1)));
        em.flush();

        notificationService.markAllRead(me.getId().toString());
        assertThat(unread(me)).isZero();
        assertThat(feed(me)).allSatisfy(n -> assertThat(n.read()).isTrue());

        follow(bob, me);

        assertThat(unread(me)).isEqualTo(1);
        assertThat(feed(me).getFirst()).satisfies(n -> {
            assertThat(n.kind()).isEqualTo(NotificationKind.FOLLOWED);
            assertThat(n.read()).isFalse();
        });
    }

    @Test
    @DisplayName("should page through the feed newest first with no item repeated or skipped")
    void shouldPageWithoutGapsWhenFollowingCursors() {
        for (int i = 0; i < 3; i++) {
            follow(persistUser("f-fan-" + i + "-" + System.nanoTime()), me);
            ballot(me, poll(alice, Audience.PUBLIC, now.minusDays(3), now.minusHours(i + 1)));
        }
        comment(bob, poll(me, Audience.PUBLIC, now.minusHours(2), now.plusDays(1)), null, "hi");
        List<NotificationResponse> all = feed(me);
        assertThat(all).hasSize(7);
        assertThat(all).extracting(NotificationResponse::createdAt)
                .isSortedAccordingTo((a, b) -> b.toInstant().compareTo(a.toInstant()));

        List<NotificationResponse> paged = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            CursorResponse<NotificationResponse> page = notificationService.list(me.getId().toString(), cursor, 2);
            paged.addAll(page.items());
            cursor = page.nextCursor();
            pages++;
        } while (cursor != null);

        assertThat(pages).isEqualTo(4);
        assertThat(paged).extracting(NotificationResponse::id)
                .containsExactlyElementsOf(all.stream().map(NotificationResponse::id).toList());
    }

    @Test
    @DisplayName("should reject a cursor issued by another list")
    void shouldRejectCursorWhenFromAnotherList() {
        String foreign = new com.vomatt.common.response.Cursor(now.toInstant().toString(), me.getId())
                .encode(com.vomatt.common.response.Cursor.Scope.FOLLOWS);

        assertThatThrownBy(() -> notificationService.list(me.getId().toString(), foreign, 2))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.COMMON_CURSOR_INVALID));
    }
}
