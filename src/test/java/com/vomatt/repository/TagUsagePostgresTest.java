package com.vomatt.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.vomatt.entity.Tag;

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
}
