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
import com.vomatt.common.response.CursorResponse;
import com.vomatt.common.security.RefreshTokenService;
import com.vomatt.entity.Audience;
import com.vomatt.entity.Invitee;
import com.vomatt.entity.NotificationKind;
import com.vomatt.entity.User;
import com.vomatt.entity.UserBlock;
import com.vomatt.entity.UserVote;
import com.vomatt.entity.Vote;
import com.vomatt.notifications.NotificationService;
import com.vomatt.notifications.dto.NotificationResponse;
import com.vomatt.repository.PostgresRepositoryTest;
import com.vomatt.users.UserMapper;
import com.vomatt.users.UserService;
import com.vomatt.votes.dto.VoteResponse;

/** Bookmarks: the bookmarked list, the per-caller flag, and the RESULT notification a bookmarked Poll gives when it Ends. */
@Import({ NotificationService.class, VoteServiceSlice.class, UserService.class, UserMapper.class })
@DisplayName("Poll bookmarks (Postgres)")
class VoteBookmarkPostgresTest extends PostgresRepositoryTest {

    @MockitoBean RefreshTokenService refreshTokenService;
    @Autowired VoteService voteService;
    @Autowired NotificationService notificationService;

    private final OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
    private User owner;
    private User me;

    @BeforeEach
    void setUp() {
        owner = persistUser("bm-owner-" + System.nanoTime());
        me = persistUser("bm-me-" + System.nanoTime());
    }

    private Vote poll(Audience audience, OffsetDateTime start, OffsetDateTime end) {
        Vote poll = persistPoll(owner, start, end, audience, "A", "B");
        em.flush();
        return poll;
    }

    private void bookmark(Vote poll) {
        voteService.bookmark(poll.getId().toString(), me.getId().toString());
        em.flush();
    }

    private List<NotificationResponse> results(User user) {
        em.flush();
        return notificationService.list(user.getId().toString(), null, null, 50).items().stream()
                .filter(n -> n.kind() == NotificationKind.RESULT).toList();
    }

    private CursorResponse<VoteResponse> bookmarked(String cursor, int limit) {
        em.flush();
        em.clear();
        return voteService.listBookmarkedVotes(me.getId().toString(), cursor, limit);
    }

    private void backdateBookmark(Vote poll, OffsetDateTime at) {
        em.createNativeQuery("UPDATE vomatt.vote_bookmarks SET created_at = :t WHERE vote_id = :v")
                .setParameter("t", at).setParameter("v", poll.getId()).executeUpdate();
    }

    @Test
    @DisplayName("should return 404 user.not_found and keep no bookmark when the account is deleted after the filter let the request in")
    void shouldThrowUserNotFoundWhenBookmarkingUserIsGone() {
        Vote open = poll(Audience.PUBLIC, now.minusHours(1), now.plusDays(1));
        em.createNativeQuery("DELETE FROM vomatt.users WHERE id = :id").setParameter("id", me.getId()).executeUpdate();

        // The insert's users guard turns the race into 0 rows, not a foreign-key failure
        assertThatThrownBy(() -> bookmark(open))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.USER_NOT_FOUND));
        assertThat(em.createNativeQuery("SELECT COUNT(*) FROM vomatt.vote_bookmarks WHERE vote_id = :v")
                .setParameter("v", open.getId()).getSingleResult()).isEqualTo(0L);
    }

    @Test
    @DisplayName("should return vote.not_found when bookmarking an Invite-only Poll the caller is not invited to")
    void shouldRejectBookmarkWhenPollIsHidden() {
        Vote hidden = poll(Audience.INVITE_ONLY, now.minusHours(1), now.plusDays(1));

        assertThatThrownBy(() -> bookmark(hidden)).isInstanceOfSatisfying(ApiException.class,
                ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.VOTE_NOT_FOUND));
    }

    @Test
    @DisplayName("should give a bookmarking non-voter RESULT only once the Poll has Ended, and only if bookmarked before it Ended")
    void shouldListResultWhenBookmarkedPollEnded() {
        Vote open = poll(Audience.PUBLIC, now.minusHours(1), now.plusDays(1));
        Vote ended = poll(Audience.PUBLIC, now.minusDays(2), now.minusHours(1));
        Vote endedBeforeBookmark = poll(Audience.PUBLIC, now.minusDays(2), now.minusHours(1));
        bookmark(open);
        bookmark(ended);
        bookmark(endedBeforeBookmark);
        backdateBookmark(ended, now.minusDays(1));

        assertThat(results(me)).singleElement().satisfies(n -> {
            assertThat(n.id()).isEqualTo("result_" + ended.getId());
            assertThat(n.createdAt().toInstant()).isEqualTo(ended.getEndTime().toInstant());
        });
    }

    @Test
    @DisplayName("should hide a blocking owner's Poll from the list and RESULT, and still let the caller remove the bookmark")
    void shouldHidePollAndAllowUnbookmarkWhenOwnerBlockedCaller() {
        Vote poll = poll(Audience.PUBLIC, now.minusDays(2), now.minusHours(1));
        bookmark(poll);
        backdateBookmark(poll, now.minusDays(1));
        em.persist(new UserBlock(owner, me));
        em.flush();

        assertThat(bookmarked(null, 10).items()).isEmpty();
        assertThat(results(me)).isEmpty();

        voteService.unbookmark(poll.getId().toString(), me.getId().toString());
        em.createNativeQuery("DELETE FROM vomatt.user_blocks").executeUpdate();

        assertThat(bookmarked(null, 10).items()).isEmpty();
        assertThat(results(me)).isEmpty();
    }

    @Test
    @DisplayName("should succeed removing a bookmark of a Poll the caller can no longer reach, or never bookmarked")
    void shouldUnbookmarkWhenPollIsHidden() {
        Vote poll = poll(Audience.INVITE_ONLY, now.minusHours(1), now.plusDays(1));
        em.persist(new Invitee(poll, me));
        em.flush();
        bookmark(poll);
        em.createNativeQuery("DELETE FROM vomatt.vote_invitees WHERE vote_id = :v").setParameter("v", poll.getId())
                .executeUpdate();

        voteService.unbookmark(poll.getId().toString(), me.getId().toString());
        voteService.unbookmark(poll.getId().toString(), me.getId().toString());

        assertThat(((Number) em.createNativeQuery("SELECT COUNT(*) FROM vomatt.vote_bookmarks WHERE user_id = :u")
                .setParameter("u", me.getId()).getSingleResult()).longValue()).isZero();
    }

    @Test
    @DisplayName("should give one RESULT when the user both voted in and bookmarked the Poll, and keep it after unbookmarking")
    void shouldListOneResultWhenVoterAlsoBookmarked() {
        Vote poll = poll(Audience.PUBLIC, now.minusDays(2), now.minusHours(1));
        em.persist(new UserVote(me, poll, poll.getOptions().iterator().next()));
        bookmark(poll);
        bookmark(poll);

        assertThat(results(me)).singleElement().satisfies(n -> assertThat(n.id()).isEqualTo("result_" + poll.getId()));
        assertThat(notificationService.countUnread(me.getId().toString(), null).count()).isEqualTo(1);

        voteService.unbookmark(poll.getId().toString(), me.getId().toString());
        assertThat(results(me)).hasSize(1);
    }

    @Test
    @DisplayName("should give no RESULT when the bookmark was removed or the Poll was Cancelled before it opened")
    void shouldListNoResultWhenUnbookmarkedOrCancelled() {
        Vote ended = poll(Audience.PUBLIC, now.minusDays(2), now.minusHours(1));
        Vote cancelled = poll(Audience.PUBLIC, now.minusHours(2), now.minusHours(3));
        bookmark(ended);
        bookmark(cancelled);
        backdateBookmark(ended, now.minusDays(1));
        backdateBookmark(cancelled, now.minusDays(1));
        voteService.unbookmark(ended.getId().toString(), me.getId().toString());

        assertThat(results(me)).isEmpty();
    }

    @Test
    @DisplayName("should drop an Invite-only Poll from RESULT and the list once the bookmarking Invitee was removed")
    void shouldHideInviteOnlyPollWhenInviteeRemoved() {
        Vote poll = poll(Audience.INVITE_ONLY, now.minusDays(2), now.minusHours(1));
        em.persist(new Invitee(poll, me));
        em.flush();
        bookmark(poll);
        assertThat(results(me)).hasSize(1);
        assertThat(bookmarked(null, 10).items()).hasSize(1);

        em.createNativeQuery("DELETE FROM vomatt.vote_invitees WHERE vote_id = :v").setParameter("v", poll.getId())
                .executeUpdate();

        assertThat(results(me)).isEmpty();
        assertThat(bookmarked(null, 10).items()).isEmpty();
    }

    @Test
    @DisplayName("should page bookmarked Polls newest bookmark first, flagged, without Deleted ones")
    void shouldPageBookmarksNewestFirstWhenListing() {
        List<Vote> polls = List.of(poll(Audience.PUBLIC, now.minusHours(1), now.plusDays(1)),
                poll(Audience.UNLISTED, now.minusDays(2), now.minusHours(1)),
                poll(Audience.PUBLIC, now.plusHours(1), now.plusDays(1)),
                poll(Audience.PUBLIC, now.minusHours(1), now.plusDays(1)));
        polls.forEach(this::bookmark);
        // One transaction shares one now(); spread the bookmark times so the order is by time, not only by id
        for (int i = 0; i < polls.size(); i++) {
            backdateBookmark(polls.get(i), now.minusMinutes(10 - i));
        }
        polls.get(3).setDeletedAt(now);
        // A repeat bookmark keeps its place
        bookmark(polls.get(0));

        CursorResponse<VoteResponse> first = bookmarked(null, 2);
        CursorResponse<VoteResponse> second = bookmarked(first.nextCursor(), 2);

        assertThat(first.items()).extracting(VoteResponse::getId)
                .containsExactly(polls.get(2).getId().toString(), polls.get(1).getId().toString());
        assertThat(second.items()).extracting(VoteResponse::getId).containsExactly(polls.get(0).getId().toString());
        assertThat(second.nextCursor()).isNull();
        assertThat(first.items()).allMatch(VoteResponse::getBookmarked);
    }

    @Test
    @DisplayName("should flag bookmarked on Poll responses for the signed-in caller only")
    void shouldFlagBookmarkedWhenCallerBookmarked() {
        Vote poll = poll(Audience.PUBLIC, now.minusHours(1), now.plusDays(1));
        String id = poll.getId().toString();
        em.clear();
        assertThat(voteService.getVote(id, me.getId().toString()).getBookmarked()).isFalse();

        voteService.bookmark(id, me.getId().toString());
        em.flush();
        em.clear();

        assertThat(voteService.getVote(id, me.getId().toString()).getBookmarked()).isTrue();
        assertThat(voteService.getVote(id, owner.getId().toString()).getBookmarked()).isFalse();
        assertThat(voteService.getVote(id, null).getBookmarked()).isNull();
    }
}
