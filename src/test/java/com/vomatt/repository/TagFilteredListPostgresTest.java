package com.vomatt.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import com.vomatt.common.response.Cursor;
import com.vomatt.common.response.CursorResponse;
import com.vomatt.entity.Audience;
import com.vomatt.entity.Tag;
import com.vomatt.entity.User;
import com.vomatt.entity.Vote;
import com.vomatt.votes.VoteListOrder;

/** Explore / Ended lists filtered by a tag, driven from vote_tags and its copied times. */
@Import(VoteListRepository.class)
@DisplayName("Tag-filtered lists (Postgres)")
class TagFilteredListPostgresTest extends PostgresRepositoryTest {

    @Autowired VoteListRepository voteListRepository;

    private final OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
    private User creator;
    private Tag tag;
    private final List<Vote> open = new ArrayList<>();
    private final List<Vote> ended = new ArrayList<>();

    private Vote poll(OffsetDateTime start, OffsetDateTime end, Audience audience, int turnout, Tag... tags) {
        Vote poll = persistPoll(creator, start, end, audience, "A", "B");
        for (Tag t : tags) {
            poll.addTag(t);
        }
        if (turnout > 0) {
            addTurnout(poll, turnout);
        }
        return poll;
    }

    @BeforeEach
    void setUp() {
        creator = persistUser("tfl-" + UUID.randomUUID());
        tag = persistTag();
        Tag other = persistTag();
        OffsetDateTime tieStart = now.minusHours(2);
        OffsetDateTime tieEnd = now.plusDays(1);
        // ties on start and end time exercise the id tiebreak
        open.add(poll(tieStart, tieEnd, Audience.PUBLIC, 3, tag));
        open.add(poll(tieStart, tieEnd, Audience.PUBLIC, 3, tag, other));
        open.add(poll(tieStart, tieEnd, Audience.PUBLIC, 0, tag));
        open.add(poll(now.minusHours(1), now.plusDays(2), Audience.PUBLIC, 3, tag));
        open.add(poll(now.minusMinutes(5), now.plusHours(1), Audience.PUBLIC, 7, tag));
        ended.add(poll(now.minusDays(3), now.minusHours(1), Audience.PUBLIC, 0, tag));
        ended.add(poll(now.minusDays(3), now.minusHours(1), Audience.PUBLIC, 0, tag));
        ended.add(poll(now.minusDays(4), now.minusHours(5), Audience.PUBLIC, 0, tag));
        // left out: Scheduled, untagged, other tag only, not Public, Deleted
        poll(now.plusHours(1), now.plusDays(1), Audience.PUBLIC, 0, tag);
        poll(now.minusHours(1), now.plusDays(1), Audience.PUBLIC, 0);
        poll(now.minusHours(1), now.plusDays(1), Audience.PUBLIC, 0, other);
        poll(now.minusHours(1), now.plusDays(1), Audience.UNLISTED, 0, tag);
        poll(now.minusHours(1), now.minusMinutes(1), Audience.PUBLIC, 0, tag).setDeletedAt(now);
        em.flush();
        em.clear();
    }

    // Walks every page two at a time, building cursors like VoteService does
    private List<UUID> walk(VoteListOrder order, Cursor.Scope scope, Function<Vote, Cursor> cursorOf) {
        List<UUID> all = new ArrayList<>();
        Cursor cursor = null;
        do {
            CursorResponse<Vote> page = CursorResponse.of(
                    voteListRepository.findPage(order, tag.getSlug(), null, cursor, now, 3, creator.getId()), 2, scope,
                    cursorOf, p -> p);
            page.items().forEach(v -> all.add(v.getId()));
            cursor = Cursor.decode(page.nextCursor(), scope);
        } while (cursor != null);
        return all;
    }

    // Reloaded for the stored Turnout; Postgres orders uuid bytewise, as lowercase hex strings do
    private List<UUID> sorted(List<Vote> polls, Comparator<Vote> keys) {
        return polls.stream().map(v -> em.find(Vote.class, v.getId())).sorted(keys.thenComparing(v -> v.getId().toString())).map(Vote::getId).toList();
    }

    @Test
    void shouldPageTagFilteredListsInKeysetOrderWhenFilteringByTag() {
        assertThat(walk(VoteListOrder.NEWEST, Cursor.Scope.FEED_NEWEST, v -> Cursor.of(v.getStartTime(), v.getId())))
                .containsExactlyElementsOf(sorted(open, Comparator.comparing(Vote::getStartTime)).reversed());
        assertThat(walk(VoteListOrder.CLOSING, Cursor.Scope.FEED_CLOSING, v -> Cursor.of(v.getEndTime(), v.getId())))
                .containsExactlyElementsOf(sorted(open, Comparator.comparing(Vote::getEndTime)));
        assertThat(walk(VoteListOrder.ENDED, Cursor.Scope.FEED_ENDED, v -> Cursor.of(v.getEndTime(), v.getId())))
                .containsExactlyElementsOf(sorted(ended, Comparator.comparing(Vote::getEndTime)).reversed());
        assertThat(walk(VoteListOrder.TOP, Cursor.Scope.FEED_TOP,
                v -> Cursor.of(v.getTurnout(), v.getStartTime(), v.getId())))
                .containsExactlyElementsOf(sorted(open, Comparator.<Vote, Integer>comparing(Vote::getTurnout)
                        .thenComparing(Vote::getStartTime)).reversed());
    }

    @Test
    void shouldWalkTagTimeIndexesWhenFilteringByTag() {
        // On a few rows the planner would rather sort; with sorts and other scans off, an ordered page can only come
        // from walking the tag's time index
        for (String setting : List.of("enable_seqscan", "enable_sort", "enable_bitmapscan")) {
            em.createNativeQuery("SET LOCAL " + setting + " = off").executeUpdate();
        }
        assertThat(scansOf("idx_vote_tags_tag_start", VoteListOrder.NEWEST)).isPositive();
        assertThat(scansOf("idx_vote_tags_tag_end", VoteListOrder.ENDED)).isPositive();
        assertThat(scansOf("idx_vote_tags_tag_end", VoteListOrder.CLOSING)).isPositive();
    }

    private long scansOf(String index, VoteListOrder order) {
        String sql = "SELECT pg_stat_get_xact_numscans(CAST('vomatt." + index + "' AS regclass))";
        long before = ((Number) em.createNativeQuery(sql).getSingleResult()).longValue();
        voteListRepository.findPage(order, tag.getSlug(), null, null, now, 3, null);
        return ((Number) em.createNativeQuery(sql).getSingleResult()).longValue() - before;
    }
}
