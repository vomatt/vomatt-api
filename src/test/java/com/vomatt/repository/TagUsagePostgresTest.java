package com.vomatt.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.vomatt.entity.Audience;
import com.vomatt.entity.Tag;
import com.vomatt.entity.User;
import com.vomatt.entity.Vote;

/** Tag usage counters: an empty id set is a valid no-op, so callers need no guard. */
@DisplayName("Tag usage counts (Postgres)")
class TagUsagePostgresTest extends PostgresRepositoryTest {

    @Autowired TagRepository tagRepository;

    @Test
    @DisplayName("should change nothing when the tag id set is empty")
    void shouldChangeNothingWhenTagIdsEmpty() {
        Tag tag = new Tag("t" + System.nanoTime() % 1_000_000_000, "slug-" + System.nanoTime(), null, 0);
        em.persist(tag);
        em.flush();

        tagRepository.incrementUsageCount(Set.of());
        tagRepository.decrementUsageCount(Set.of());
        em.clear();

        assertThat(em.find(Tag.class, tag.getId()).getUsageCount()).isZero();
    }

    @Test
    @DisplayName("should release only the user's counted Polls when the user's tag usage is released")
    void shouldDecrementOnlyCountedPollsWhenUserPollsReleased() {
        User owner = persistUser("tag-owner-" + System.nanoTime());
        User other = persistUser("tag-other-" + System.nanoTime());
        Tag shared = persistTag();
        Tag uncounted = persistTag();
        OffsetDateTime now = OffsetDateTime.now();
        Vote first = persistPoll(owner, now.minusHours(1), now.plusDays(1), "A", "B");
        Vote second = persistPoll(owner, now.minusHours(1), now.plusDays(1), "A", "B");
        Vote othersPoll = persistPoll(other, now.minusHours(1), now.plusDays(1), "A", "B");
        Vote unlisted = persistPoll(owner, now.minusHours(1), now.plusDays(1), Audience.UNLISTED, "A", "B");
        Vote deleted = persistPoll(owner, now.minusHours(1), now.plusDays(1), "A", "B");
        deleted.setDeletedAt(now);
        first.addTag(shared);
        second.addTag(shared);
        othersPoll.addTag(shared);
        unlisted.addTag(uncounted);
        deleted.addTag(uncounted);
        em.flush();
        // As VoteService counts them: shared by three Public Polls; the Unlisted and the Deleted Poll are not counted
        em.createNativeQuery("UPDATE vomatt.tags SET usage_count = CASE WHEN id = :shared THEN 3 ELSE 1 END WHERE id IN (:shared, :uncounted)")
                .setParameter("shared", shared.getId()).setParameter("uncounted", uncounted.getId()).executeUpdate();

        tagRepository.decrementUsageCountForUserPolls(owner.getId());

        assertThat(em.find(Tag.class, shared.getId()).getUsageCount()).isEqualTo(1);
        assertThat(em.find(Tag.class, uncounted.getId()).getUsageCount()).isEqualTo(1);
    }
}
