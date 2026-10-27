package com.vomatt.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.vomatt.entity.Feedback;
import com.vomatt.entity.User;

@DisplayName("FeedbackRepository (Postgres)")
class FeedbackPostgresTest extends PostgresRepositoryTest {

    @Autowired FeedbackRepository feedbackRepository;

    @Test
    @DisplayName("should store feedback against the baseline table")
    void shouldPersistFeedbackWhenSaved() {
        User user = persistUser("feedbacker");
        Feedback saved = feedbackRepository.saveAndFlush(new Feedback(user, "Please add dark mode."));
        em.clear();

        Feedback found = feedbackRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getContent()).isEqualTo("Please add dark mode.");
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("should page newest first and filter by sender")
    void shouldFilterBySenderWhenUserIdGiven() {
        User alice = persistUser("alice-fb");
        User bob = persistUser("bob-fb");
        feedbackRepository.saveAndFlush(new Feedback(alice, "first"));
        feedbackRepository.saveAndFlush(new Feedback(bob, "second"));
        em.clear();

        assertThat(feedbackRepository.findPage(null, org.springframework.data.domain.PageRequest.of(0, 20)).getContent())
                .extracting(Feedback::getContent).containsSubsequence("second", "first");
        assertThat(feedbackRepository.findPage(alice.getId(), org.springframework.data.domain.PageRequest.of(0, 20)).getContent())
                .extracting(Feedback::getContent).containsExactly("first");
        assertThat(feedbackRepository.findByUserIdOrderByCreatedAt(bob.getId()))
                .extracting(Feedback::getContent).containsExactly("second");
    }
}
