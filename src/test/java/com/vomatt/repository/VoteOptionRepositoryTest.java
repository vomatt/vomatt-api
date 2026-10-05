package com.vomatt.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.vomatt.entity.User;
import com.vomatt.entity.Vote;
import com.vomatt.entity.VoteOption;

@DisplayName("VoteOptionRepository (Postgres)")
class VoteOptionRepositoryTest extends PostgresRepositoryTest {

    @Autowired VoteOptionRepository voteOptionRepository;

    @Test
    @DisplayName("應該在 Poll 儲存後依 displayOrder 讀回選項")
    void shouldReturnOptionsInDisplayOrderWhenPollSaved() {
        User owner = persistUser("owner");
        OffsetDateTime now = OffsetDateTime.now();
        Vote poll = persistPoll(owner, now, now.plusDays(1), "A", "B", "C");
        em.flush();
        em.clear();

        assertThat(voteOptionRepository.findByVoteIdOrderByDisplayOrder(poll.getId()))
                .extracting(VoteOption::getText)
                .containsExactly("A", "B", "C");
    }
}
