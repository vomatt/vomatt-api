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
    private <E> List<E> walk(int limit, Function<Cursor, List<E>> fetch, Function<E, Cursor> cursorOf) {
        List<E> all = new ArrayList<>();
        Cursor cursor = null;
        do {
            CursorResponse<E> page = CursorResponse.of(fetch.apply(cursor), limit, cursorOf, p -> p);
            all.addAll(page.items());
            cursor = Cursor.decode(page.nextCursor());
        } while (cursor != null);
        return all;
    }

    @Test
    @DisplayName("應該讓留言依新到舊分頁、不含已刪除、不重複")
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

        List<VoteComment> all = walk(2,
                c -> commentRepository.findPageByVoteId(poll.getId(), c == null ? null : c.timeKey(),
                        c == null ? null : c.id(), Limit.of(3)),
                c -> Cursor.of(c.getCreatedAt(), c.getId()));

        assertThat(all).extracting(VoteComment::getContent).containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("應該讓使用者搜尋依 username 分頁且不重複")
    void shouldPageUserSearchByUsername() {
        String marker = "us" + System.nanoTime();
        for (String suffix : List.of("d", "a", "c", "b", "e")) {
            persistUser(marker + suffix);
        }
        em.flush();

        List<User> all = walk(2,
                c -> userRepository.searchByUsernameAfter(marker, c == null ? null : c.key(), Limit.of(3)),
                u -> Cursor.of(u.getUsername(), u.getId()));

        assertThat(all).extracting(User::getUsername)
                .containsExactly(marker + "a", marker + "b", marker + "c", marker + "d", marker + "e");
    }

    @Test
    @DisplayName("應該讓熱門標籤依使用次數分頁，同分時不重複也不遺漏")
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

        List<Tag> all = walk(2,
                c -> tagRepository.findPopularPage(c == null ? null : (int) c.longKey(), c == null ? null : c.id(),
                        Limit.of(3)),
                t -> Cursor.of(t.getUsageCount(), t.getId()));

        List<Tag> ours = all.stream().filter(t -> ids.contains(t.getId())).toList();
        assertThat(ours).hasSize(5).doesNotHaveDuplicates();
        assertThat(ours).extracting(Tag::getUsageCount).isSortedAccordingTo((a, b) -> b - a);
    }
}
