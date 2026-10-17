package com.vomatt.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Limit;

import com.vomatt.common.response.Cursor;
import com.vomatt.common.response.CursorResponse;
import com.vomatt.entity.Tag;
import com.vomatt.entity.User;
import com.vomatt.entity.Vote;
import com.vomatt.entity.VoteComment;

/** Keyset queries behind the comments, user search and popular tags lists. */
@DisplayName("Cursor lists (Postgres)")
class CursorListsPostgresTest extends PostgresRepositoryTest {

    @Autowired VoteCommentRepository commentRepository;
    @Autowired UserRepository userRepository;
    @Autowired TagRepository tagRepository;

    // Walks every page; fetch(cursor, limit + 1) returns raw rows, like the services do
    private <E> List<E> walk(int limit, Cursor.Scope scope, Function<Cursor, List<E>> fetch, Function<E, Cursor> cursorOf) {
        List<E> all = new ArrayList<>();
        Cursor cursor = null;
        do {
            CursorResponse<E> page = CursorResponse.of(fetch.apply(cursor), limit, scope, cursorOf, p -> p);
            all.addAll(page.items());
            cursor = Cursor.decode(page.nextCursor(), scope);
        } while (cursor != null);
        return all;
    }

    @Test
    @DisplayName("should paginate comments newest first, excluding deleted, without duplicates")
    void shouldPageCommentsNewestFirstWithoutDeleted() {
        User user = persistUser("cl-" + UUID.randomUUID());
        OffsetDateTime now = OffsetDateTime.now();
        Vote poll = persistPoll(user, now.minusHours(1), now.plusDays(1), "A", "B");
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            em.persist(new VoteComment(poll, user, "c" + i));
            expected.addFirst("c" + i);
        }
        VoteComment deleted = new VoteComment(poll, user, "deleted");
        deleted.softDelete();
        em.persist(deleted);
        em.flush();
        em.clear();

        List<VoteComment> all = walk(2, Cursor.Scope.COMMENTS,
                c -> commentRepository.findPageByVoteId(poll.getId(), c == null ? null : c.timeKey(),
                        c == null ? null : c.id(), Limit.of(3)),
                c -> Cursor.of(c.getCreatedAt(), c.getId()));

        assertThat(all).extracting(VoteComment::getContent).containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("should paginate user search by username without duplicates")
    void shouldPageUserSearchByUsername() {
        String marker = "us" + System.nanoTime();
        for (String suffix : List.of("d", "a", "c", "b", "e")) {
            persistUser(marker + suffix);
        }
        em.flush();

        List<User> all = walk(2, Cursor.Scope.USER_SEARCH,
                c -> userRepository.searchByUsernameAfter(marker, c == null ? null : c.key(), Limit.of(3)),
                u -> Cursor.of(u.getUsername(), u.getId()));

        assertThat(all).extracting(User::getUsername)
                .containsExactly(marker + "a", marker + "b", marker + "c", marker + "d", marker + "e");
    }

    @Test
    @DisplayName("should match a username fragment case-insensitively and literally through the bigram index")
    void shouldMatchUsernameFragmentWhenSearchingByBigrams() {
        String marker = "ub" + System.nanoTime();
        persistUser(marker + "_Ab");
        persistUser(marker + "xab");
        em.flush();

        assertThat(userRepository.searchByUsernameAfter(marker.toUpperCase() + "_aB", null, Limit.of(10)))
                .extracting(User::getUsername).containsExactly(marker + "_Ab");
        // One character has no bigrams: LIKE alone still filters
        assertThat(userRepository.searchByUsernameAfter("_", null, Limit.of(1000)))
                .extracting(User::getUsername).contains(marker + "_Ab").doesNotContain(marker + "xab");

        em.createNativeQuery("SET LOCAL enable_seqscan = off").executeUpdate();
        String plan = em.createNativeQuery(
                "EXPLAIN SELECT 1 FROM vomatt.users u WHERE u.username_bigrams @> vomatt.text_bigrams('" + marker + "')")
                .getResultList().toString();
        assertThat(plan).contains("idx_users_username_bigrams");
    }

    @Test
    @DisplayName("should paginate popular tags by usage count with no duplicates or gaps on ties")
    void shouldPagePopularTagsWithTies() {
        List<UUID> ids = new ArrayList<>();
        int[] usage = { 5, 3, 3, 3, 1 };
        for (int i = 0; i < usage.length; i++) {
            Tag tag = new Tag("pop" + i + "-" + System.nanoTime() % 100000, "pop-" + i + "-" + System.nanoTime(), null, 0);
            tag.setUsageCount(usage[i] + 100_000);  // above any tag other tests create
            em.persist(tag);
            ids.add(tag.getId());
        }
        em.flush();

        List<Tag> all = walk(2, Cursor.Scope.POPULAR_TAGS,
                c -> tagRepository.findPopularPage(c == null ? null : (int) c.longKey(), c == null ? null : c.id(),
                        Limit.of(3)),
                t -> Cursor.of(t.getUsageCount(), t.getId()));

        List<Tag> ours = all.stream().filter(t -> ids.contains(t.getId())).toList();
        assertThat(ours).hasSize(5).doesNotHaveDuplicates();
        assertThat(ours).extracting(Tag::getUsageCount).isSortedAccordingTo((a, b) -> b - a);
    }
}
