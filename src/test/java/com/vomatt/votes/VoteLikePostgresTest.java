package com.vomatt.votes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.security.RefreshTokenService;
import com.vomatt.common.security.UserPrincipal;
import com.vomatt.entity.Audience;
import com.vomatt.entity.NotificationCategory;
import com.vomatt.entity.NotificationKind;
import com.vomatt.entity.User;
import com.vomatt.entity.UserBlock;
import com.vomatt.entity.Vote;
import com.vomatt.notifications.NotificationService;
import com.vomatt.notifications.dto.NotificationResponse;
import com.vomatt.notifications.dto.UpdateNotificationPreferencesRequest;
import com.vomatt.repository.PostgresRepositoryTest;
import com.vomatt.users.UserMapper;
import com.vomatt.users.UserService;
import com.vomatt.votes.dto.VoteResponse;

/** Poll likes: the stored like count, the per-caller flag, and the owner's POLL_LIKE notification. */
@Import({ NotificationService.class, VoteServiceSlice.class, UserService.class, UserMapper.class,
        com.vomatt.common.storage.StorageService.class })
@DisplayName("Poll likes (Postgres)")
class VoteLikePostgresTest extends PostgresRepositoryTest {

    @MockitoBean RefreshTokenService refreshTokenService;
    @Autowired VoteService voteService;
    @Autowired NotificationService notificationService;
    @Autowired UserService userService;

    private final OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
    private User owner;
    private User me;

    @BeforeEach
    void setUp() {
        owner = persistUser("lk-owner-" + System.nanoTime());
        me = persistUser("lk-me-" + System.nanoTime());
    }

    private Vote poll(Audience audience) {
        Vote poll = persistPoll(owner, now.minusHours(1), now.plusDays(1), audience, "A", "B");
        em.flush();
        return poll;
    }

    private void like(Vote poll, User user) {
        voteService.like(poll.getId().toString(), user.getId().toString());
        em.flush();
    }

    private void unlike(Vote poll, User user) {
        voteService.unlike(poll.getId().toString(), user.getId().toString());
        em.flush();
    }

    private VoteResponse view(Vote poll, User viewer) {
        em.flush();
        em.clear();
        return voteService.getVote(poll.getId().toString(), viewer == null ? null : viewer.getId().toString());
    }

    private List<NotificationResponse> pollLikes(User user) {
        em.flush();
        return notificationService.list(user.getId().toString(), NotificationCategory.LIKE, null, 50).items().stream()
                .filter(n -> n.kind() == NotificationKind.POLL_LIKE).toList();
    }

    @Test
    @DisplayName("should count each user's like once and flag it for that user only")
    void shouldCountLikesOnceWhenLikedRepeatedly() {
        Vote poll = poll(Audience.PUBLIC);
        like(poll, me);
        like(poll, me);
        like(poll, owner);

        assertThat(view(poll, me)).satisfies(r -> {
            assertThat(r.getLikeCount()).isEqualTo(2);
            assertThat(r.isLikedByMe()).isTrue();
        });
        assertThat(view(poll, null).isLikedByMe()).isFalse();

        unlike(poll, me);
        unlike(poll, me);

        assertThat(view(poll, me)).satisfies(r -> {
            assertThat(r.getLikeCount()).isEqualTo(1);
            assertThat(r.isLikedByMe()).isFalse();
        });
    }

    @Test
    @DisplayName("should keep the like count visible while the results are Sealed")
    void shouldShowLikeCountWhenPollIsSealed() {
        Vote poll = poll(Audience.PUBLIC);
        like(poll, me);

        assertThat(view(poll, null)).satisfies(r -> {
            assertThat(r.getOptions()).allSatisfy(o -> assertThat(o.getVotes()).isNull());
            assertThat(r.getLikeCount()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("should return vote.not_found when liking a Poll hidden from the caller, and still allow unliking it")
    void shouldRejectLikeWhenPollIsHidden() {
        Vote hidden = poll(Audience.INVITE_ONLY);
        Vote blocked = poll(Audience.PUBLIC);
        like(blocked, me);
        em.persist(new UserBlock(owner, me));
        em.flush();

        assertThatThrownBy(() -> like(hidden, me)).isInstanceOfSatisfying(ApiException.class,
                ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.VOTE_NOT_FOUND));
        assertThatThrownBy(() -> like(blocked, me)).isInstanceOfSatisfying(ApiException.class,
                ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.VOTE_NOT_FOUND));
        assertThat(pollLikes(owner)).isEmpty();

        unlike(blocked, me);
        unlike(hidden, me);
        assertThat(view(blocked, owner).getLikeCount()).isZero();
    }

    @Test
    @DisplayName("should return 404 user.not_found and keep no like when the account is deleted after the filter let the request in")
    void shouldThrowUserNotFoundWhenLikingUserIsGone() {
        Vote poll = poll(Audience.PUBLIC);
        em.createNativeQuery("DELETE FROM vomatt.users WHERE id = :id").setParameter("id", me.getId()).executeUpdate();

        assertThatThrownBy(() -> like(poll, me))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.USER_NOT_FOUND));
        assertThat(view(poll, owner).getLikeCount()).isZero();
    }

    @Test
    @DisplayName("should release the deleted account's likes from the like count")
    void shouldDecrementLikeCountWhenLikerIsDeleted() {
        Vote poll = poll(Audience.PUBLIC);
        like(poll, me);
        like(poll, owner);

        userService.deleteUser(me.getId().toString(), new UserPrincipal(me.getId().toString(), List.of("user"), null));

        assertThat(view(poll, owner).getLikeCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("should give the owner POLL_LIKE for others' likes only, drop it on unlike and give a new unread one on re-like")
    void shouldNotifyOwnerWhenSomeoneElseLikesPoll() {
        Vote poll = poll(Audience.PUBLIC);
        like(poll, owner);
        assertThat(pollLikes(owner)).isEmpty();

        like(poll, me);
        NotificationResponse first = pollLikes(owner).getFirst();
        assertThat(pollLikes(owner)).singleElement().satisfies(n -> {
            assertThat(n.category()).isEqualTo(NotificationCategory.LIKE);
            assertThat(n.id()).startsWith("poll_like_");
            assertThat(n.actor().id()).isEqualTo(me.getId().toString());
            assertThat(n.poll().id()).isEqualTo(poll.getId().toString());
            assertThat(n.comment()).isNull();
        });
        notificationService.markRead(owner.getId().toString(), first.id());
        assertThat(pollLikes(owner).getFirst().read()).isTrue();

        unlike(poll, me);
        assertThat(pollLikes(owner)).isEmpty();

        like(poll, me);
        assertThat(pollLikes(owner)).singleElement().satisfies(n -> {
            assertThat(n.id()).isNotEqualTo(first.id());
            assertThat(n.read()).isFalse();
        });
    }

    @Test
    @DisplayName("should leave POLL_LIKE out when the likes switch is off or the liker is blocked")
    void shouldHidePollLikeWhenSwitchedOffOrBlocked() {
        Vote poll = poll(Audience.PUBLIC);
        like(poll, me);

        notificationService.updatePreferences(owner.getId().toString(),
                new UpdateNotificationPreferencesRequest(null, null, null, null, null, false));
        assertThat(pollLikes(owner)).isEmpty();

        notificationService.updatePreferences(owner.getId().toString(),
                new UpdateNotificationPreferencesRequest(null, null, null, null, null, true));
        em.persist(new UserBlock(owner, me));
        assertThat(pollLikes(owner)).isEmpty();
    }
}
