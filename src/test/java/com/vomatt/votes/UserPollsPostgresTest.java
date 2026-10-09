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
import org.springframework.http.HttpStatus;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.response.CursorResponse;
import com.vomatt.entity.User;
import com.vomatt.entity.Vote;
import com.vomatt.repository.PostgresRepositoryTest;
import com.vomatt.votes.dto.VoteResponse;

@Import(VoteServiceSlice.class)
@DisplayName("User Polls (Postgres)")
class UserPollsPostgresTest extends PostgresRepositoryTest {

    @Autowired VoteService voteService;

    private final OffsetDateTime now = OffsetDateTime.now();
    private User owner;
    private User other;

    @BeforeEach
    void setUp() {
        owner = persistUser("owner-" + System.nanoTime());
        other = persistUser("other-" + System.nanoTime());
    }

    private void poll(User creator, String title, OffsetDateTime start, OffsetDateTime end) {
        Vote vote = persistPoll(creator, start, end, "A", "B");
        vote.setTitle(title);
    }

    private CursorResponse<VoteResponse> page(String cursor, Integer limit) {
        em.flush();
        em.clear();
        return voteService.getUserPolls(owner.getUsername(), cursor, limit, null);
    }

    private static List<String> titles(CursorResponse<VoteResponse> page) {
        return page.items().stream().map(VoteResponse::getTitle).toList();
    }

    @Test
    @DisplayName("應該列出使用者的 Open 與 Ended Poll，排除 Scheduled 與開放前就取消的，依開放時間新到舊")
    void shouldListUserPollsExcludingScheduled() {
        poll(owner, "open", now.minusHours(1), now.plusDays(1));
        poll(owner, "ended", now.minusDays(3), now.minusDays(1));
        poll(owner, "scheduled", now.plusHours(1), now.plusDays(1));
        // Closed while Scheduled: end before start (CONTEXT.md: Cancelled)
        poll(owner, "cancelled", now.minusHours(2), now.minusHours(3));
        poll(other, "someone-else", now.minusHours(1), now.plusDays(1));

        assertThat(titles(page(null, null))).containsExactly("open", "ended");
    }

    @Test
    @DisplayName("應該以 cursor 分頁且不重複")
    void shouldPageWithCursor() {
        poll(owner, "newest", now.minusHours(1), now.plusDays(1));
        poll(owner, "middle", now.minusHours(2), now.plusDays(1));
        poll(owner, "oldest", now.minusHours(3), now.plusDays(1));

        CursorResponse<VoteResponse> first = page(null, 2);
        CursorResponse<VoteResponse> second = page(first.nextCursor(), 2);

        assertThat(titles(first)).containsExactly("newest", "middle");
        assertThat(titles(second)).containsExactly("oldest");
        assertThat(second.nextCursor()).isNull();
    }

    @Test
    @DisplayName("應該在使用者已停權時回 404")
    void shouldReturnNotFoundWhenUserSuspended() {
        poll(owner, "open", now.minusHours(1), now.plusDays(1));
        owner.setActive(false);

        assertThatThrownBy(() -> page(null, null))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(ex.getMessageKey()).isEqualTo(MessageKey.USER_NOT_FOUND);
                });
    }

    @Test
    @DisplayName("應該在使用者不存在時回 404")
    void shouldReturnNotFoundWhenUserMissing() {
        assertThatThrownBy(() -> voteService.getUserPolls("nobody-" + System.nanoTime(), null, null, null))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(ex.getMessageKey()).isEqualTo(MessageKey.USER_NOT_FOUND);
                });
    }
}
