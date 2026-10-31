package com.vomatt.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.response.CursorResponse;
import com.vomatt.common.security.RefreshTokenService;
import com.vomatt.entity.Audience;
import com.vomatt.entity.CommentLike;
import com.vomatt.entity.Invitee;
import com.vomatt.entity.NotificationCategory;
import com.vomatt.entity.NotificationKind;
import com.vomatt.entity.User;
import com.vomatt.entity.UserBlock;
import com.vomatt.entity.UserFollow;
import com.vomatt.entity.UserVote;
import com.vomatt.entity.Vote;
import com.vomatt.entity.VoteBookmark;
import com.vomatt.entity.VoteComment;
import com.vomatt.entity.VoteOption;
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

    // Postgres keeps microseconds; a nanosecond clock would make times read back from SQL differ from the persisted ones
    private final OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
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
        return notificationService.list(user.getId().toString(), null, null, 50).items();
    }

    private List<NotificationKind> kinds(User user) {
        return feed(user).stream().map(NotificationResponse::kind).toList();
    }

    private long unread(User user) {
        em.flush();
        return notificationService.countUnread(user.getId().toString(), null).count();
    }

    private void preferences(User user, Boolean resultsIn, Boolean invitations, Boolean comments, Boolean closingSoon) {
        notificationService.updatePreferences(user.getId().toString(),
                new UpdateNotificationPreferencesRequest(resultsIn, invitations, comments, closingSoon, null, null));
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
        addTurnout(poll, 1);
    }

    private VoteComment comment(User author, Vote poll, VoteComment parent, String text) {
        VoteComment comment = new VoteComment(poll, author, text);
        comment.setParent(parent);
        em.persist(comment);
        return comment;
    }

    private CommentLike like(User user, VoteComment comment) {
        CommentLike like = new CommentLike(comment, user);
        em.persist(like);
        return like;
    }

    private VoteComment mention(User author, Vote poll, VoteComment parent, User... mentioned) {
        VoteComment comment = comment(author, poll, parent, "hey");
        comment.setMentionedUserIds(java.util.Arrays.stream(mentioned).map(User::getId).toArray(java.util.UUID[]::new));
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
    @DisplayName("should give no RESULT to the owner, an Invitee or a bookmark when a Poll Ended with no Ballots, and one when it holds a Ballot")
    void shouldNotListResultWhenPollEndedWithNoBallots() {
        Vote emptyInviteOnly = invitePoll(me, now.minusHours(1), now.plusDays(2), alice);
        Vote emptyPublic = poll(me, Audience.PUBLIC, now.minusHours(1), now.plusDays(2));
        Vote voted = poll(me, Audience.PUBLIC, now.minusHours(1), now.plusDays(2));
        ballot(alice, voted);
        em.persist(new VoteBookmark(bob, emptyPublic));
        em.persist(new VoteBookmark(bob, voted));
        em.flush();
        OffsetDateTime ended = OffsetDateTime.now();
        for (Vote poll : List.of(emptyInviteOnly, emptyPublic, voted)) {
            poll.setEndTime(ended);
        }

        for (User user : List.of(me, alice, bob)) {
            assertThat(feed(user)).extracting(NotificationResponse::id).containsExactly("result_" + voted.getId());
            assertThat(unread(user)).isEqualTo(1);
        }
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
        addTurnout(open, 1);
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
    @DisplayName("should list LIKE for others' likes on the user's Comments, not the user's own like or a like on a deleted Comment")
    void shouldListLikeWhenOthersLikeUsersComment() {
        Vote alices = poll(alice, Audience.PUBLIC, now.minusHours(2), now.plusDays(1));
        VoteComment myComment = comment(me, alices, null, "Spaces");
        VoteComment deleted = comment(me, alices, null, "gone");
        VoteComment alicesComment = comment(alice, alices, null, "Tabs");
        CommentLike like = like(bob, myComment);
        like(me, myComment);
        like(bob, deleted);
        like(bob, alicesComment);
        deleted.softDelete();

        assertThat(feed(me)).singleElement().satisfies(n -> {
            assertThat(n.id()).isEqualTo("like_" + like.getId());
            assertThat(n.kind()).isEqualTo(NotificationKind.LIKE);
            assertThat(n.category()).isEqualTo(NotificationCategory.LIKE);
            assertThat(n.actor().username()).isEqualTo(bob.getUsername());
            assertThat(n.poll().id()).isEqualTo(alices.getId().toString());
            assertThat(n.comment().id()).isEqualTo(myComment.getId().toString());
        });
        assertThat(unread(me)).isEqualTo(1);
    }

    @Test
    @DisplayName("should list and count only the requested category, and nothing when its kinds are switched off")
    void shouldFilterByCategoryWhenRequested() {
        Vote mine = poll(me, Audience.PUBLIC, now.minusDays(2), now.minusHours(1));
        ballot(bob, mine);
        VoteComment onMine = comment(alice, mine, null, "hi");
        VoteComment myComment = comment(me, mine, null, "thanks");
        like(bob, myComment);
        follow(alice, me);
        String userId = me.getId().toString();
        em.flush();

        assertThat(notificationService.list(userId, NotificationCategory.CONVERSATION, null, 50).items())
                .extracting(NotificationResponse::id).containsExactly("comment_" + onMine.getId());
        assertThat(notificationService.list(userId, NotificationCategory.POLL_END, null, 50).items())
                .extracting(NotificationResponse::kind).containsExactly(NotificationKind.RESULT);
        assertThat(notificationService.countUnread(userId, NotificationCategory.LIKE).count()).isEqualTo(1);
        assertThat(notificationService.countUnread(userId, NotificationCategory.INVITATION).count()).isZero();

        preferences(me, null, null, false, null);

        assertThat(notificationService.list(userId, NotificationCategory.CONVERSATION, null, 50).items()).isEmpty();
        assertThat(notificationService.countUnread(userId, NotificationCategory.CONVERSATION).count()).isZero();
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
    @DisplayName("should list MENTION with the author and Comment when someone else mentions the user, and mark it read")
    void shouldListMentionWhenOthersMentionUser() {
        Vote alices = poll(alice, Audience.PUBLIC, now.minusHours(2), now.plusDays(1));
        VoteComment mentioned = mention(bob, alices, null, me, alice);

        assertThat(feed(me)).singleElement().satisfies(n -> {
            assertThat(n.kind()).isEqualTo(NotificationKind.MENTION);
            assertThat(n.id()).isEqualTo("mention_" + mentioned.getId());
            assertThat(n.actor().username()).isEqualTo(bob.getUsername());
            assertThat(n.poll().id()).isEqualTo(alices.getId().toString());
            assertThat(n.comment().id()).isEqualTo(mentioned.getId().toString());
        });
        notificationService.markRead(me.getId().toString(), "mention_" + mentioned.getId());
        assertThat(feed(me)).singleElement().satisfies(n -> assertThat(n.read()).isTrue());
    }

    @Test
    @DisplayName("should not list MENTION for a self-mention, a deleted Comment, a blocked author or when switched off")
    void shouldNotListMentionWhenSelfDeletedBlockedOrSwitchedOff() {
        User carol = persistUser("f-carol-" + System.nanoTime());
        Vote alices = poll(alice, Audience.PUBLIC, now.minusHours(2), now.plusDays(1));
        mention(me, alices, null, me);
        mention(bob, alices, null, me).softDelete();
        mention(carol, alices, null, me);
        em.persist(new UserBlock(me, carol));
        assertThat(feed(me)).isEmpty();

        mention(bob, alices, null, me);
        assertThat(kinds(me)).containsExactly(NotificationKind.MENTION);
        notificationService.updatePreferences(me.getId().toString(),
                new UpdateNotificationPreferencesRequest(null, null, null, null, false, null));
        assertThat(feed(me)).isEmpty();
        assertThat(unread(me)).isZero();
    }

    @Test
    @DisplayName("should list only COMMENT when the mentioning Comment is on the user's Poll or replies to the user's Comment")
    void shouldNotDuplicateCommentWithMentionWhenBothApply() {
        Vote mine = poll(me, Audience.PUBLIC, now.minusHours(2), now.plusDays(1));
        Vote alices = poll(alice, Audience.PUBLIC, now.minusHours(2), now.plusDays(1));
        VoteComment onMine = mention(alice, mine, null, me);
        VoteComment myComment = comment(me, alices, null, "mine");
        VoteComment reply = mention(bob, alices, myComment, me);

        assertThat(feed(me)).extracting(NotificationResponse::id).containsExactlyInAnyOrder(
                "comment_" + onMine.getId(), "comment_" + reply.getId());
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    @DisplayName("should list MENTION for a Reply that mentions the user under their deleted Comment, whether COMMENT is on or off")
    void shouldListMentionWhenReplyUnderDeletedComment(boolean commentsOn) {
        Vote alices = poll(alice, Audience.PUBLIC, now.minusHours(2), now.plusDays(1));
        VoteComment myDeleted = comment(me, alices, null, "gone");
        myDeleted.softDelete();
        VoteComment reply = mention(bob, alices, myDeleted, me);
        preferences(me, null, null, commentsOn, null);

        assertThat(feed(me)).extracting(NotificationResponse::id).containsExactly("mention_" + reply.getId());
        assertThat(unread(me)).isEqualTo(1);
    }

    @Test
    @DisplayName("should return notification.not_found when reading a MENTION the feed lists as COMMENT, and read it once COMMENT is off")
    void shouldNotReadMentionWhenFeedListsItAsComment() {
        Vote mine = poll(me, Audience.PUBLIC, now.minusHours(2), now.plusDays(1));
        VoteComment onMine = mention(alice, mine, null, me);
        em.flush();

        assertThatThrownBy(() -> notificationService.markRead(me.getId().toString(), "mention_" + onMine.getId()))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.NOTIFICATION_NOT_FOUND));

        preferences(me, null, null, false, null);
        notificationService.markRead(me.getId().toString(), "mention_" + onMine.getId());
        assertThat(unread(me)).isZero();
    }

    @Test
    @DisplayName("should keep a Comment read as COMMENT read when switching COMMENT off lists it as MENTION")
    void shouldKeepReadWhenCommentReadThenSwitchedOff() {
        Vote mine = poll(me, Audience.PUBLIC, now.minusHours(2), now.plusDays(1));
        VoteComment onMine = mention(alice, mine, null, me);
        em.flush();
        notificationService.markRead(me.getId().toString(), "comment_" + onMine.getId());

        preferences(me, null, null, false, null);

        assertThat(feed(me)).singleElement().satisfies(n -> {
            assertThat(n.id()).isEqualTo("mention_" + onMine.getId());
            assertThat(n.read()).isTrue();
        });
        assertThat(unread(me)).isZero();
    }

    @Test
    @DisplayName("should keep a Comment read as MENTION read when switching COMMENT on lists it as COMMENT")
    void shouldKeepReadWhenMentionReadThenCommentSwitchedOn() {
        Vote mine = poll(me, Audience.PUBLIC, now.minusHours(2), now.plusDays(1));
        VoteComment onMine = mention(alice, mine, null, me);
        preferences(me, null, null, false, null);
        em.flush();
        notificationService.markRead(me.getId().toString(), "mention_" + onMine.getId());

        preferences(me, null, null, true, null);
        // The upsert is native SQL; drop the preference rows the first read cached
        em.clear();

        assertThat(feed(me)).singleElement().satisfies(n -> {
            assertThat(n.id()).isEqualTo("comment_" + onMine.getId());
            assertThat(n.read()).isTrue();
        });
        assertThat(unread(me)).isZero();
    }

    @Test
    @DisplayName("should list MENTION for what would be COMMENT when COMMENT is switched off")
    void shouldListMentionWhenCommentSwitchedOff() {
        Vote mine = poll(me, Audience.PUBLIC, now.minusHours(2), now.plusDays(1));
        Vote alices = poll(alice, Audience.PUBLIC, now.minusHours(2), now.plusDays(1));
        VoteComment onMine = mention(alice, mine, null, me);
        VoteComment reply = mention(bob, alices, comment(me, alices, null, "mine"), me);
        preferences(me, null, null, false, null);

        assertThat(feed(me)).extracting(NotificationResponse::id).containsExactlyInAnyOrder(
                "mention_" + onMine.getId(), "mention_" + reply.getId());
        assertThat(unread(me)).isEqualTo(2);
    }

    @Test
    @DisplayName("should not list MENTION for a Reply in a thread started by a user the user blocked")
    void shouldNotListMentionWhenThreadStarterBlocked() {
        User carol = persistUser("f-carol-" + System.nanoTime());
        Vote alices = poll(alice, Audience.PUBLIC, now.minusHours(2), now.plusDays(1));
        mention(bob, alices, comment(carol, alices, null, "thread"), me);
        assertThat(kinds(me)).containsExactly(NotificationKind.MENTION);

        em.persist(new UserBlock(me, carol));

        assertThat(feed(me)).isEmpty();
    }

    @Test
    @DisplayName("should list MENTION on an Invite-only Poll only while the user is invited")
    void shouldNotListMentionWhenInviteOnlyPollNotVisible() {
        Vote hidden = poll(alice, Audience.INVITE_ONLY, now.minusHours(2), now.plusDays(1));
        mention(bob, hidden, null, me);
        assertThat(feed(me)).isEmpty();

        Vote invited = invitePoll(alice, now.minusHours(2), now.plusDays(1), me);
        VoteComment visible = mention(bob, invited, null, me);
        assertThat(feed(me)).filteredOn(n -> n.kind() == NotificationKind.MENTION)
                .extracting(NotificationResponse::id).containsExactly("mention_" + visible.getId());
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
    @DisplayName("should leave out every notification about a Poll whose owner blocked the user, until unblocked")
    void shouldNotListPollNotificationsWhenOwnerBlockedUser() {
        Vote ended = poll(alice, Audience.PUBLIC, now.minusDays(2), now.minusHours(1));
        ballot(me, ended);
        Vote open = poll(alice, Audience.PUBLIC, now.minusHours(2), now.plusDays(1));
        VoteComment mine = comment(me, open, null, "my comment on alice's Poll");
        comment(bob, open, mine, "bob replies");
        invitePoll(alice, now.minusHours(1), now.plusDays(2), me);
        UserBlock block = new UserBlock(alice, me);
        em.persist(block);

        assertThat(feed(me)).isEmpty();
        assertThat(unread(me)).isZero();

        em.remove(block);

        assertThat(kinds(me)).containsExactlyInAnyOrder(NotificationKind.RESULT, NotificationKind.COMMENT,
                NotificationKind.INVITATION);
    }

    @Test
    @DisplayName("should not list a Reply in a thread started by a user the user blocked, as the Comment lists hide it")
    void shouldNotListCommentWhenThreadStarterBlocked() {
        User carol = persistUser("f-carol-" + System.nanoTime());
        Vote mine = poll(me, Audience.PUBLIC, now.minusHours(2), now.plusDays(1));
        VoteComment carolsThread = comment(carol, mine, null, "carol starts");
        VoteComment hidden = comment(bob, mine, carolsThread, "bob replies to carol");
        VoteComment shown = comment(bob, mine, null, "bob starts");
        em.persist(new UserBlock(me, carol));

        assertThat(feed(me)).extracting(NotificationResponse::id).containsExactly("comment_" + shown.getId());
        assertThat(feed(me)).extracting(NotificationResponse::id).doesNotContain("comment_" + hidden.getId());
    }

    @Test
    @DisplayName("should leave switched-off kinds out of the feed and the unread count, and keep FOLLOWED")
    void shouldFilterKindsWhenSwitchedOff() {
        Vote mine = poll(me, Audience.PUBLIC, now.minusDays(2), now.minusHours(1));
        ballot(bob, mine);
        comment(alice, mine, null, "hi");
        follow(alice, me);
        invitePoll(bob, now.minusHours(1), now.plusDays(2), me);
        assertThat(unread(me)).isEqualTo(4);

        preferences(me, false, false, false, null);

        assertThat(kinds(me)).containsExactly(NotificationKind.FOLLOWED);
        assertThat(unread(me)).isEqualTo(1);
        assertThat(notificationService.getPreferences(me.getId().toString()))
                .extracting("resultsIn", "invitations", "comments", "closingSoon", "mentions").containsExactly(false, false, false, false, true);
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
            CursorResponse<NotificationResponse> page = notificationService.list(me.getId().toString(), null, cursor, 2);
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

        assertThatThrownBy(() -> notificationService.list(me.getId().toString(), null, foreign, 2))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.COMMON_CURSOR_INVALID));
    }

    /** Sets each option's stored count (display order), then reloads everything so the feed reads them back. */
    private void counts(Vote poll, int... counts) {
        List<VoteOption> options = poll.getOptions().stream()
                .sorted(Comparator.comparingInt(VoteOption::getDisplayOrder)).toList();
        em.flush();
        for (int i = 0; i < counts.length; i++) {
            em.createNativeQuery("UPDATE vomatt.vote_options SET vote_count = :count WHERE id = :id")
                    .setParameter("count", counts[i]).setParameter("id", options.get(i).getId()).executeUpdate();
        }
    }

    private VoteOption option(Vote poll, String text) {
        return poll.getOptions().stream().filter(o -> o.getText().equals(text)).findFirst().orElseThrow();
    }

    private NotificationResponse.PollSummary reloadedPoll(User user) {
        em.flush();
        em.clear();
        return notificationService.list(user.getId().toString(), null, null, 50).items().getFirst().poll();
    }

    @Test
    @DisplayName("should give an Ended Poll's start, the user's option and the single winner when one option has the most Ballots")
    void shouldGiveWinnerAndMyOptionWhenPollEnded() {
        Vote ended = persistPoll(alice, now.minusDays(2), now.minusHours(1), "A", "B", "C");
        em.persist(new UserVote(me, ended, option(ended, "B")));
        addTurnout(ended, 1);
        counts(ended, 3, 1, 0);

        NotificationResponse.PollSummary poll = reloadedPoll(me);

        assertThat(poll.startTime().toInstant()).isEqualTo(ended.getStartTime().toInstant());
        assertThat(poll.myOptionId()).isEqualTo(option(ended, "B").getId().toString());
        assertThat(poll.winningOptionIds()).containsExactly(option(ended, "A").getId().toString());
        assertThat(poll.winningOptionLabels()).containsExactly("A");
        assertThat(poll.tie()).isFalse();
    }

    @Test
    @DisplayName("should name every top option in display order and flag a tie when options share the most Ballots")
    void shouldFlagTieWhenTopOptionsShareMostBallots() {
        Vote ended = persistPoll(alice, now.minusDays(2), now.minusHours(1), "A", "B", "C");
        ballot(me, ended);
        counts(ended, 1, 2, 2);

        NotificationResponse.PollSummary poll = reloadedPoll(me);

        assertThat(poll.winningOptionLabels()).containsExactly("B", "C");
        assertThat(poll.tie()).isTrue();
    }

    @Test
    @DisplayName("should give no winner and no tie when an Ended Poll holds no Ballot naming an option")
    void shouldGiveNoWinnerWhenEndedPollHasNoCounts() {
        Vote ended = persistPoll(me, now.minusDays(2), now.minusHours(1), "A", "B");
        addTurnout(ended, 1); // a sealed Anonymous Ballot: counted in Turnout, names no option

        NotificationResponse.PollSummary poll = reloadedPoll(me);

        assertThat(poll.myOptionId()).isNull();
        assertThat(poll.winningOptionIds()).isEmpty();
        assertThat(poll.winningOptionLabels()).isEmpty();
        assertThat(poll.tie()).isFalse();
        assertThat(poll.id()).isEqualTo(ended.getId().toString());
    }

    @Test
    @DisplayName("should keep the outcome null but give the user's option while the Poll is Sealed")
    void shouldSealOutcomeWhenPollNotEnded() {
        Vote open = invitePoll(alice, now.minusHours(1), now.plusDays(2), me);
        em.persist(new UserVote(me, open, option(open, "A")));
        counts(open, 5, 1);

        NotificationResponse.PollSummary poll = reloadedPoll(me);

        assertThat(poll.status()).isEqualTo(VoteStatus.OPEN);
        assertThat(poll.myOptionId()).isEqualTo(option(open, "A").getId().toString());
        assertThat(poll.winningOptionIds()).isNull();
        assertThat(poll.winningOptionLabels()).isNull();
        assertThat(poll.tie()).isNull();
    }

    @Test
    @DisplayName("should give no option for the user's Anonymous Ballot once it is sealed")
    void shouldGiveNoMyOptionWhenAnonymousBallotSealed() {
        Vote ended = persistPoll(alice, now.minusDays(2), now.minusHours(1), "A", "B");
        UserVote sealed = new UserVote(me, ended, null);
        sealed.setAnonymous(true);
        em.persist(sealed);
        addTurnout(ended, 1);

        assertThat(reloadedPoll(me).myOptionId()).isNull();
    }

    @Test
    @DisplayName("should tell, for each actor, whether the user follows them and whether they follow the user")
    void shouldGiveFollowsBothWaysWhenActorListed() {
        follow(alice, me);
        follow(me, alice);
        follow(bob, me);
        Vote mine = poll(me, Audience.PUBLIC, now.minusHours(2), now.plusDays(1));
        User carol = persistUser("f-carol-" + System.nanoTime());
        comment(carol, mine, null, "hi");
        follow(me, carol);

        List<NotificationResponse> feed = feed(me);

        assertThat(feed).filteredOn(n -> n.actor().username().equals(alice.getUsername())).singleElement()
                .satisfies(n -> assertThat(n.actor()).extracting("isFollowing", "followsYou").containsExactly(true, true));
        assertThat(feed).filteredOn(n -> n.actor().username().equals(bob.getUsername())).singleElement()
                .satisfies(n -> assertThat(n.actor()).extracting("isFollowing", "followsYou").containsExactly(false, true));
        assertThat(feed).filteredOn(n -> n.actor().username().equals(carol.getUsername())).singleElement()
                .satisfies(n -> assertThat(n.actor()).extracting("isFollowing", "followsYou").containsExactly(true, false));
    }

    @Test
    @DisplayName("should give a Reply's parent Comment id, and none for a top-level Comment")
    void shouldGiveParentIdWhenCommentIsReply() {
        Vote mine = poll(me, Audience.PUBLIC, now.minusHours(2), now.plusDays(1));
        Vote alices = poll(alice, Audience.PUBLIC, now.minusHours(2), now.plusDays(1));
        VoteComment topLevel = comment(bob, mine, null, "top");
        VoteComment myComment = comment(me, alices, null, "mine");
        VoteComment reply = comment(bob, alices, myComment, "reply");

        List<NotificationResponse> feed = feed(me);

        assertThat(feed).filteredOn(n -> n.comment().id().equals(topLevel.getId().toString())).singleElement()
                .satisfies(n -> assertThat(n.comment().parentId()).isNull());
        assertThat(feed).filteredOn(n -> n.comment().id().equals(reply.getId().toString())).singleElement()
                .satisfies(n -> assertThat(n.comment().parentId()).isEqualTo(myComment.getId().toString()));
    }

    /** {@code each} notifications of every kind that carries an actor, a Poll or a Comment, from distinct users. */
    private void feedOf(User user, int each) {
        Vote host = poll(alice, Audience.PUBLIC, now.minusHours(2), now.plusDays(1));
        VoteComment userComment = comment(user, host, null, "mine");
        for (int i = 0; i < each; i++) {
            User other = persistUser("f-other-" + i + "-" + System.nanoTime());
            follow(other, user);
            comment(other, host, userComment, "reply " + i);
            invitePoll(other, now.minusHours(1), now.plusDays(2), user);
            Vote ended = persistPoll(other, now.minusDays(2), now.minusHours(i + 1), "A", "B");
            ballot(user, ended);
            counts(ended, 1, 0);
        }
    }

    @Test
    @DisplayName("should run as many queries for a page of twenty notifications as for a page of four")
    void shouldRunSameQueriesWhenPageGrows() {
        feedOf(me, 1);
        feedOf(bob, 5);
        em.flush();
        em.clear();
        Statistics stats = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);

        stats.clear();
        assertThat(notificationService.list(me.getId().toString(), null, null, 50).items()).hasSize(4);
        long smallPage = stats.getPrepareStatementCount();
        em.clear();
        stats.clear();
        assertThat(notificationService.list(bob.getId().toString(), null, null, 50).items()).hasSize(20);
        long largePage = stats.getPrepareStatementCount();

        assertThat(largePage).isEqualTo(smallPage);
    }
}
