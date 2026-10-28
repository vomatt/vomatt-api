package com.vomatt.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import com.vomatt.common.response.Cursor;
import com.vomatt.common.response.CursorResponse;
import com.vomatt.entity.Tag;
import com.vomatt.entity.User;
import com.vomatt.entity.UserBlock;
import com.vomatt.entity.UserFollow;
import com.vomatt.entity.Vote;

/** The Following list: followed users' and followed tags' Open Public Polls. */
@Import(VoteListRepository.class)
@DisplayName("Following feed (Postgres)")
class FollowingFeedPostgresTest extends PostgresRepositoryTest {

    @Autowired VoteListRepository voteListRepository;
    @Autowired TagRepository tagRepository;

    private User viewer;
    private Tag tag;
    private Tag otherTag;
    private OffsetDateTime now;

    @BeforeEach
    void setUp() {
        viewer = persistUser("ff-viewer-" + UUID.randomUUID());
        tag = persistTag();
        otherTag = persistTag();
        now = OffsetDateTime.now();
    }

    private Tag persistTag() {
        String marker = "ff" + System.nanoTime();
        Tag t = new Tag(marker, marker, null, 0);
        em.persist(t);
        return t;
    }

    private Vote poll(User creator, int startedMinutesAgo, Tag... tags) {
        Vote poll = persistPoll(creator, now.minusMinutes(startedMinutesAgo), now.plusDays(1), "A", "B");
        for (Tag t : tags) {
            poll.addTag(t);
        }
        return poll;
    }

    // Walks every page, like the service does
    private List<UUID> walk(int limit) {
        em.flush();
        em.clear();
        List<UUID> all = new ArrayList<>();
        Cursor cursor = null;
        do {
            CursorResponse<Vote> page = CursorResponse.of(
                    voteListRepository.findFollowing(viewer.getId(), cursor, now, limit + 1), limit,
                    Cursor.Scope.FEED_FOLLOWING, v -> Cursor.of(v.getStartTime(), v.getId()), p -> p);
            page.items().forEach(v -> all.add(v.getId()));
            cursor = Cursor.decode(page.nextCursor(), Cursor.Scope.FEED_FOLLOWING);
        } while (cursor != null);
        return all;
    }

    @Test
    @DisplayName("should list followed users' and followed tags' Polls newest first, each once")
    void shouldMergeFollowedUsersAndTagsWithoutDuplicates() {
        User followed = persistUser("ff-followed-" + UUID.randomUUID());
        User stranger = persistUser("ff-stranger-" + UUID.randomUUID());
        em.persist(new UserFollow(viewer, followed));
        Vote both = poll(followed, 1, tag, otherTag);   // followed user and two followed tags
        Vote byTag = poll(stranger, 2, tag);
        Vote byUser = poll(followed, 3);
        poll(stranger, 4);                               // neither followed
        em.flush();
        tagRepository.follow(viewer.getId(), tag.getId());
        tagRepository.follow(viewer.getId(), otherTag.getId());

        assertThat(walk(1)).containsExactly(both.getId(), byTag.getId(), byUser.getId());
    }

    @Test
    @DisplayName("should leave out the caller's own, blocked, blocking and suspended creators' tagged Polls without shortening pages")
    void shouldExcludeFilteredCreatorsWhenFollowingTag() {
        User stranger = persistUser("ff-stranger-" + UUID.randomUUID());
        User blocked = persistUser("ff-blocked-" + UUID.randomUUID());
        User blocking = persistUser("ff-blocking-" + UUID.randomUUID());
        User suspended = persistUser("ff-suspended-" + UUID.randomUUID());
        suspended.setActive(false);
        em.persist(new UserBlock(viewer, blocked));
        em.persist(new UserBlock(blocking, viewer));
        // Newer filtered Polls fill the tag's first page; the valid one sits behind them
        poll(viewer, 1, tag);
        poll(blocked, 2, tag);
        poll(blocking, 3, tag);
        poll(suspended, 4, tag);
        Vote valid = poll(stranger, 5, tag);
        em.flush();
        tagRepository.follow(viewer.getId(), tag.getId());

        assertThat(walk(2)).containsExactly(valid.getId());
    }

    @Test
    @DisplayName("should drop a tag's Polls once unfollowed, idempotently")
    void shouldDropTagPollsWhenUnfollowed() {
        User stranger = persistUser("ff-stranger-" + UUID.randomUUID());
        poll(stranger, 1, tag);
        em.flush();
        assertThat(tagRepository.follow(viewer.getId(), tag.getId())).isEqualTo(1);
        assertThat(tagRepository.follow(viewer.getId(), tag.getId())).isZero();
        assertThat(tagRepository.findFollowedBy(viewer.getId())).extracting(Tag::getId).containsExactly(tag.getId());

        tagRepository.unfollow(viewer.getId(), tag.getId());

        assertThat(walk(20)).isEmpty();
    }

    @Test
    @DisplayName("should insert nothing when following an unknown tag")
    void shouldInsertNothingWhenTagUnknown() {
        assertThat(tagRepository.follow(viewer.getId(), UUID.randomUUID())).isZero();
    }

    @Test
    @DisplayName("should export the user's tag follows oldest first")
    void shouldExportTagFollowsWhenFollowing() {
        tagRepository.follow(viewer.getId(), tag.getId());

        assertThat(tagRepository.findFollowsOf(viewer.getId())).singleElement().satisfies(f -> {
            assertThat(f.getTagId()).isEqualTo(tag.getId());
            assertThat(f.getCreatedAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("should leave out a followed tag's Ended and Scheduled Polls")
    void shouldExcludeNotOpenPollsWhenFollowingTag() {
        User stranger = persistUser("ff-stranger-" + UUID.randomUUID());
        persistPoll(stranger, now.minusDays(2), now.minusDays(1), "A", "B").addTag(tag);
        persistPoll(stranger, now.plusDays(1), now.plusDays(2), "A", "B").addTag(tag);
        Vote open = poll(stranger, 1, tag);
        em.flush();
        tagRepository.follow(viewer.getId(), tag.getId());

        assertThat(walk(20)).containsExactly(open.getId());
    }
}
