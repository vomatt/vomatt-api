package com.vomatt.votes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
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
            CursorResponse<VoteResponse> page = voteService.listVotes(order, tag.getSlug(), null, cursor, limit, null);
            page.items().forEach(v -> titles.add(v.getTitle()));
            cursor = page.nextCursor();
        } while (cursor != null);
        return titles;
    }

    @Test
    @DisplayName("should include only Open Polls in Feed, by start time newest first, excluding Scheduled and Ended")
    void shouldExcludeScheduledFromExplore() {
        poll("old-open", now.minusDays(3), now.plusDays(1));
        poll("new-open", now.minusHours(1), now.plusDays(5));
        poll("scheduled", now.plusHours(1), now.plusDays(1));
        poll("ended", now.minusDays(3), now.minusDays(1));
        poll("cancelled", now.plusDays(1), now.minusMinutes(1));

        assertThat(titles(VoteListOrder.NEWEST, null)).containsExactly("new-open", "old-open");
    }

    @Test
    @DisplayName("should sort closing by end time soonest first")
    void shouldOrderByClosingSoonest() {
        poll("closes-late", now.minusDays(1), now.plusDays(9));
        poll("closes-soon", now.minusDays(1), now.plusHours(2));

        assertThat(titles(VoteListOrder.CLOSING, null)).containsExactly("closes-soon", "closes-late");
    }

    @Test
    @DisplayName("should sort Ended by end time newest first")
    void shouldOrderEndedByMostRecentlyEnded() {
        poll("ended-long-ago", now.minusDays(9), now.minusDays(5));
        poll("ended-recently", now.minusDays(9), now.minusHours(1));
        poll("open", now.minusDays(1), now.plusDays(1));

        assertThat(titles(VoteListOrder.ENDED, null)).containsExactly("ended-recently", "ended-long-ago");
    }

    @Test
    @DisplayName("should paginate by cursor without duplicates or gaps (including equal sort keys)")
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
    @DisplayName("should attach myOptionId, participantCount and commentCount to list items (including replies, excluding deleted)")
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

        List<VoteResponse> items = voteService.listVotes(VoteListOrder.NEWEST, tag.getSlug(), null, null, null,
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
    @DisplayName("should keep query count per page constant regardless of row count")
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
        voteService.listVotes(VoteListOrder.NEWEST, tag.getSlug(), null, null, 2, voter.getId().toString());
        long smallPage = stats.getPrepareStatementCount();
        em.clear();
        stats.clear();
        voteService.listVotes(VoteListOrder.NEWEST, tag.getSlug(), null, null, 12, voter.getId().toString());
        long largePage = stats.getPrepareStatementCount();

        assertThat(largePage).isEqualTo(smallPage);
    }

    private List<String> search(VoteListOrder order, String q) {
        em.flush();
        em.clear();
        return voteService.listVotes(order, tag.getSlug(), q, null, 50, null).items().stream()
                .map(VoteResponse::getTitle).toList();
    }

    @Test
    @DisplayName("should search title and description with two-character Chinese query, not option text")
    void shouldFindPollsByTwoCharacterChineseQuery() {
        poll("今天午餐吃什麼", now.minusHours(1), now.plusDays(1));
        Vote byDescription = poll("週末計畫", now.minusHours(2), now.plusDays(1));
        byDescription.setDescription("想去吃午餐");
        Vote optionOnly = poll("其他", now.minusHours(3), now.plusDays(1));
        optionOnly.getOptions().iterator().next().setText("午餐");
        poll("午 餐 分開", now.minusHours(4), now.plusDays(1));

        assertThat(search(VoteListOrder.NEWEST, "午餐")).containsExactly("今天午餐吃什麼", "週末計畫");
    }

    @Test
    @DisplayName("should ignore case and sort by end time when searching Ended Polls")
    void shouldSearchEndedPollsCaseInsensitively() {
        poll("Best IDE ever", now.minusDays(3), now.minusDays(2));
        poll("which ide now", now.minusDays(3), now.minusHours(1));
        poll("open ide", now.minusDays(1), now.plusDays(1));

        assertThat(search(VoteListOrder.ENDED, "IDE")).containsExactly("which ide now", "Best IDE ever");
    }

    @Test
    @DisplayName("should treat % and _ as literal characters")
    void shouldEscapeLikeWildcardsInQuery() {
        poll("100% sure", now.minusHours(1), now.plusDays(1));
        poll("1000 sure", now.minusHours(2), now.plusDays(1));
        poll("snake_case", now.minusHours(3), now.plusDays(1));
        poll("snakeXcase", now.minusHours(4), now.plusDays(1));

        assertThat(search(VoteListOrder.NEWEST, "0%")).containsExactly("100% sure");
        assertThat(search(VoteListOrder.NEWEST, "e_c")).containsExactly("snake_case");
    }

    @Test
    @DisplayName("should reject search query shorter than 2 characters")
    void shouldRejectQueryShorterThanTwoChars() {
        assertThatThrownBy(() -> voteService.listVotes(VoteListOrder.NEWEST, null, "午", null, null, null))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.VOTE_SEARCH_QUERY_TOO_SHORT));
    }

    @Test
    @DisplayName("should let two-character query use bigram GIN index")
    void shouldUseBigramIndexForTwoCharacterQuery() {
        em.createNativeQuery("SET LOCAL enable_seqscan = off").executeUpdate();
        @SuppressWarnings("unchecked")
        List<String> plan = em.createNativeQuery(
                "EXPLAIN SELECT id FROM vomatt.votes WHERE search_bigrams @> vomatt.text_bigrams('午餐')")
                .getResultList();

        assertThat(String.join("\n", plan)).contains("idx_votes_search_bigrams");
    }

    @Test
    @DisplayName("should reject a Feed newest cursor with 400 COMMON_CURSOR_INVALID when sent with sort=closing")
    void shouldRejectCursorWhenOrderingChanged() {
        poll("a", now.minusHours(2), now.plusDays(1));
        poll("b", now.minusHours(1), now.plusDays(2));
        em.flush();
        String newestCursor = voteService.listVotes(VoteListOrder.NEWEST, tag.getSlug(), null, null, 1, null).nextCursor();

        assertThatThrownBy(() -> voteService.listVotes(VoteListOrder.CLOSING, tag.getSlug(), null, newestCursor, 1, null))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.COMMON_CURSOR_INVALID));
        // filters are not part of the scope: the same ordering under another tag still accepts it
        assertThat(voteService.listVotes(VoteListOrder.NEWEST, null, null, newestCursor, 1, null)).isNotNull();
    }
}
