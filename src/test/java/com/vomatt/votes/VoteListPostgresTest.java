package com.vomatt.votes;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import com.vomatt.common.response.CursorResponse;
import com.vomatt.entity.Tag;
import com.vomatt.entity.User;
import com.vomatt.entity.UserVote;
import com.vomatt.entity.Vote;
import com.vomatt.entity.VoteComment;
import com.vomatt.entity.VoteOption;
import com.vomatt.repository.PostgresRepositoryTest;
import com.vomatt.votes.dto.VoteResponse;

/**
 * Feed / Explore keyset lists. Each test runs in a rolled-back transaction; tests filter on a unique tag
 * so rows committed by other test classes never interfere.
 */
@Import(VoteServiceSlice.class)
@DisplayName("Poll lists (Postgres)")
class VoteListPostgresTest extends PostgresRepositoryTest {

    @Autowired VoteService voteService;

    private final OffsetDateTime now = OffsetDateTime.now();
    private User owner;
    private Tag tag;

    @BeforeEach
    void setUp() {
        owner = persistUser("list-owner-" + System.nanoTime());
        tag = new Tag("t" + System.nanoTime() % 1_000_000_000, "slug-" + System.nanoTime(), null, 0);
        em.persist(tag);
    }

    private Vote poll(String title, OffsetDateTime start, OffsetDateTime end) {
        Vote vote = persistPoll(owner, start, end, "A", "B");
        vote.setTitle(title);
        vote.addTag(tag);
        return vote;
    }

    private List<String> titles(VoteListOrder order, Integer limit) {
        em.flush();
        em.clear();
        List<String> titles = new ArrayList<>();
        String cursor = null;
        do {
            CursorResponse<VoteResponse> page = voteService.listVotes(order, tag.getSlug(), cursor, limit, null);
            page.items().forEach(v -> titles.add(v.getTitle()));
            cursor = page.nextCursor();
        } while (cursor != null);
        return titles;
    }

    @Test
    @DisplayName("應該讓 Feed 只含 Open Poll，依開放時間新到舊，不含 Scheduled 與 Ended")
    void shouldExcludeScheduledFromExplore() {
        poll("old-open", now.minusDays(3), now.plusDays(1));
        poll("new-open", now.minusHours(1), now.plusDays(5));
        poll("scheduled", now.plusHours(1), now.plusDays(1));
        poll("ended", now.minusDays(3), now.minusDays(1));
        poll("cancelled", now.plusDays(1), now.minusMinutes(1));

        assertThat(titles(VoteListOrder.NEWEST, null)).containsExactly("new-open", "old-open");
    }

    @Test
    @DisplayName("應該讓 closing 依結束時間由近到遠")
    void shouldOrderByClosingSoonest() {
        poll("closes-late", now.minusDays(1), now.plusDays(9));
        poll("closes-soon", now.minusDays(1), now.plusHours(2));

        assertThat(titles(VoteListOrder.CLOSING, null)).containsExactly("closes-soon", "closes-late");
    }

    @Test
    @DisplayName("應該讓 Ended 依結束時間新到舊")
    void shouldOrderEndedByMostRecentlyEnded() {
        poll("ended-long-ago", now.minusDays(9), now.minusDays(5));
        poll("ended-recently", now.minusDays(9), now.minusHours(1));
        poll("open", now.minusDays(1), now.plusDays(1));

        assertThat(titles(VoteListOrder.ENDED, null)).containsExactly("ended-recently", "ended-long-ago");
    }

    @Test
    @DisplayName("應該在 cursor 分頁時不重複也不遺漏（含相同排序鍵）")
    void shouldNotRepeatItemsAcrossCursorPages() {
        OffsetDateTime sameStart = now.minusHours(2);
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            poll("p" + i, i < 3 ? sameStart : now.minusMinutes(i), now.plusDays(1));
            expected.add("p" + i);
        }

        assertThat(titles(VoteListOrder.NEWEST, 2)).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(expected);
        assertThat(titles(VoteListOrder.CLOSING, 3)).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    @DisplayName("應該在列表項目附上 myOptionId、participantCount 與 commentCount（含回覆、不含已刪除）")
    void shouldAttachSelectionTurnoutAndCommentCount() {
        Vote voted = poll("voted", now.minusHours(1), now.plusDays(1));
        poll("untouched", now.minusHours(2), now.plusDays(1));
        User voter = persistUser("list-voter-" + System.nanoTime());
        VoteOption chosen = voted.getOptions().iterator().next();
        em.persist(new UserVote(voter, voted, chosen, null));
        em.flush();
        em.createNativeQuery("UPDATE vomatt.vote_options SET vote_count = 1 WHERE id = :id")
                .setParameter("id", chosen.getId()).executeUpdate();
        em.persist(new VoteComment(voted, voter, "hello"));
        VoteComment deleted = new VoteComment(voted, voter, "gone");
        deleted.softDelete();
        em.persist(deleted);
        em.flush();
        em.clear();

        List<VoteResponse> items = voteService.listVotes(VoteListOrder.NEWEST, tag.getSlug(), null, null,
                voter.getId().toString()).items();

        VoteResponse first = items.getFirst();
        assertThat(first.getTitle()).isEqualTo("voted");
        assertThat(first.getMyOptionId()).isEqualTo(chosen.getId().toString());
        assertThat(first.getParticipantCount()).isEqualTo(1);
        assertThat(first.getCommentCount()).isEqualTo(1);
        assertThat(items.get(1).getMyOptionId()).isNull();
        assertThat(items.get(1).getCommentCount()).isZero();
    }

    @Test
    @DisplayName("應該讓每頁查詢數固定，不隨筆數成長")
    void shouldUseConstantQueriesPerPage() {
        User voter = persistUser("list-q-" + System.nanoTime());
        for (int i = 0; i < 12; i++) {
            poll("q" + i, now.minusMinutes(i + 1), now.plusDays(1));
        }
        em.flush();
        em.clear();
        Statistics stats = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);

        stats.clear();
        voteService.listVotes(VoteListOrder.NEWEST, tag.getSlug(), null, 2, voter.getId().toString());
        long smallPage = stats.getPrepareStatementCount();
        em.clear();
        stats.clear();
        voteService.listVotes(VoteListOrder.NEWEST, tag.getSlug(), null, 12, voter.getId().toString());
        long largePage = stats.getPrepareStatementCount();

        assertThat(largePage).isEqualTo(smallPage);
    }
}
