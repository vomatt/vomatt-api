package com.vomatt.votes;

import com.vomatt.entity.User;
import com.vomatt.entity.Vote;
import com.vomatt.entity.VoteOption;
import com.vomatt.votes.dto.VoteResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("VoteMapper")
class VoteMapperTest {

    private final VoteMapper mapper = new VoteMapper();

    private VoteResponse.VoteOptionResponse mapOneOption(OffsetDateTime endTime) {
        Vote vote = new Vote();
        ReflectionTestUtils.setField(vote, "id", UUID.randomUUID());
        vote.setCreator(User.builder().id(UUID.randomUUID()).username("mei").build());
        vote.setStartTime(OffsetDateTime.now().minusDays(1));
        vote.setEndTime(endTime);
        VoteOption option = new VoteOption("Saturday", null, vote);
        ReflectionTestUtils.setField(option, "id", UUID.randomUUID());

        VoteResponse response = mapper.toResponse(vote, List.of(option), Map.of(option.getId(), 3L), 3L);
        return response.getOptions().getFirst();
    }

    @Test
    @DisplayName("投票進行中時應隱藏各選項票數")
    void shouldWithholdOptionCountsWhileOpen() {
        assertThat(mapOneOption(OffsetDateTime.now().plusDays(1)).getVotes()).isNull();
    }

    @Test
    @DisplayName("投票結束後應公開各選項票數")
    void shouldRevealOptionCountsOnceEnded() {
        assertThat(mapOneOption(OffsetDateTime.now().minusMinutes(1)).getVotes()).isEqualTo(3L);
    }
}
