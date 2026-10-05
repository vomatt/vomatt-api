package com.vomatt.votes;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.vomatt.entity.User;
import com.vomatt.entity.Vote;
import com.vomatt.entity.VoteOption;
import com.vomatt.votes.dto.VoteResponse;
import com.vomatt.votes.dto.VoteResultResponse;

@DisplayName("VoteMapper (sealing)")
class VoteMapperTest {

    private final VoteMapper mapper = new VoteMapper();
    private final OffsetDateTime now = OffsetDateTime.now();

    private Vote poll(OffsetDateTime start, OffsetDateTime end) {
        Vote vote = new Vote();
        ReflectionTestUtils.setField(vote, "id", UUID.randomUUID());
        vote.setCreator(User.builder().id(UUID.randomUUID()).username("owner").build());
        vote.setStartTime(start);
        vote.setEndTime(end);
        return vote;
    }

    private VoteOption option(String text, int count) {
        VoteOption option = new VoteOption(text, null, null);
        ReflectionTestUtils.setField(option, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(option, "voteCount", count);
        return option;
    }

    @Test
    @DisplayName("應該在 Poll Open 時隱藏各選項票數，但 Turnout 仍可見")
    void shouldOmitOptionCountsWhenPollOpen() {
        VoteResponse response = mapper.toResponse(poll(now.minusHours(1), now.plusDays(1)),
                List.of(option("A", 3), option("B", 1)));

        assertThat(response.getOptions()).extracting(VoteResponse.VoteOptionResponse::getVotes).containsOnlyNulls();
        assertThat(response.getParticipantCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("應該在 Poll Scheduled 時隱藏各選項票數")
    void shouldOmitOptionCountsWhenPollScheduled() {
        VoteResponse response = mapper.toResponse(poll(now.plusHours(1), now.plusDays(1)), List.of(option("A", 0)));

        assertThat(response.getOptions()).extracting(VoteResponse.VoteOptionResponse::getVotes).containsOnlyNulls();
    }

    @Test
    @DisplayName("應該在 Poll Ended 後公開各選項票數")
    void shouldRevealOptionCountsWhenPollEnded() {
        VoteResponse response = mapper.toResponse(poll(now.minusDays(2), now.minusDays(1)),
                List.of(option("A", 3), option("B", 1)));

        assertThat(response.getOptions()).extracting(VoteResponse.VoteOptionResponse::getVotes).containsExactly(3L, 1L);
        assertThat(response.getParticipantCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("應該以 Participant 為分母計算 Support")
    void shouldComputeSupportFromParticipants() {
        VoteResultResponse results = mapper.toResultResponse(poll(now.minusDays(2), now.minusDays(1)),
                List.of(option("A", 3), option("B", 1)));

        assertThat(results.getTotalParticipants()).isEqualTo(4);
        assertThat(results.getOptions()).extracting(VoteResultResponse.VoteOptionResultResponse::getPercentage)
                .containsExactly(75.0, 25.0);
    }
}
