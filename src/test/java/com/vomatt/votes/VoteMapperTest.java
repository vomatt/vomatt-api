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
import com.vomatt.users.PublicName;
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
        vote.scheduleEnd(end);
        return vote;
    }

    // Turnout is the stored counter; the Ballot path keeps it equal to the sum of option counts
    private Vote poll(OffsetDateTime start, OffsetDateTime end, int turnout) {
        Vote vote = poll(start, end);
        ReflectionTestUtils.setField(vote, "turnout", turnout);
        return vote;
    }

    private VoteOption option(String text, int count) {
        VoteOption option = new VoteOption(text, null, null);
        ReflectionTestUtils.setField(option, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(option, "voteCount", count);
        return option;
    }

    @Test
    @DisplayName("should hide option counts while Poll is Open but keep Turnout visible")
    void shouldOmitOptionCountsWhenPollOpen() {
        VoteResponse response = mapper.toResponse(poll(now.minusHours(1), now.plusDays(1), 4),
                List.of(option("A", 3), option("B", 1)), PublicName.NONE);

        assertThat(response.getOptions()).extracting(VoteResponse.VoteOptionResponse::getVotes).containsOnlyNulls();
        assertThat(response.getParticipantCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("should hide option counts when Poll is Scheduled")
    void shouldOmitOptionCountsWhenPollScheduled() {
        VoteResponse response = mapper.toResponse(poll(now.plusHours(1), now.plusDays(1)), List.of(option("A", 0)), PublicName.NONE);

        assertThat(response.getOptions()).extracting(VoteResponse.VoteOptionResponse::getVotes).containsOnlyNulls();
    }

    @Test
    @DisplayName("should reveal option counts after Poll has Ended")
    void shouldRevealOptionCountsWhenPollEnded() {
        VoteResponse response = mapper.toResponse(poll(now.minusDays(2), now.minusDays(1), 4),
                List.of(option("A", 3), option("B", 1)), PublicName.NONE);

        assertThat(response.getOptions()).extracting(VoteResponse.VoteOptionResponse::getVotes).containsExactly(3L, 1L);
        assertThat(response.getParticipantCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("should return empty tags when Poll tags are null")
    void shouldReturnEmptyTagsWhenTagsNull() {
        Vote vote = poll(now.minusHours(1), now.plusDays(1));
        vote.setTags(null);

        assertThat(mapper.toResponse(vote, List.of(), PublicName.NONE).getTags()).isEmpty();
    }

    @Test
    @DisplayName("should compute Support with Participant as denominator")
    void shouldComputeSupportFromParticipants() {
        VoteResultResponse results = mapper.toResultResponse(poll(now.minusDays(2), now.minusDays(1), 4),
                List.of(option("A", 3), option("B", 1)), PublicName.NONE);

        assertThat(results.getTotalParticipants()).isEqualTo(4);
        assertThat(results.getDeadline()).isEqualTo(now.minusDays(1));
        assertThat(results.getOptions()).extracting(VoteResultResponse.VoteOptionResultResponse::getPercentage)
                .containsExactly(75.0, 25.0);
    }

    @Test
    @DisplayName("should keep option counts and winners Sealed after the Poll Ended until its reveal time, Turnout visible")
    void shouldSealResultsWhenEndedBeforeRevealAt() {
        Vote vote = poll(now.minusDays(2), now.minusDays(1), 4);
        vote.setRevealAt(now.plusHours(1));
        vote.setPro(true);
        VoteResponse response = mapper.toResponse(vote, List.of(option("A", 3), option("B", 1)), PublicName.NONE);

        assertThat(response.getOptions()).extracting(VoteResponse.VoteOptionResponse::getVotes).containsOnlyNulls();
        assertThat(response.getParticipantCount()).isEqualTo(4);
        assertThat(response.isPro()).isTrue();
        assertThat(response.getRevealAt()).isEqualTo(vote.getRevealAt());
        assertThat(mapper.toWinners(vote)).isNull();
    }

    @Test
    @DisplayName("should show option counts once the reveal time passed")
    void shouldUnsealResultsWhenRevealAtPassed() {
        Vote vote = poll(now.minusDays(2), now.minusDays(1), 4);
        vote.setRevealAt(now.minusHours(1));
        VoteResponse response = mapper.toResponse(vote, List.of(option("A", 3), option("B", 1)), PublicName.NONE);

        assertThat(response.getOptions()).extracting(VoteResponse.VoteOptionResponse::getVotes).containsExactly(3L, 1L);
    }

    @Test
    @DisplayName("should carry the owner's public names on Poll and result responses")
    void shouldSetCreatorNamesWhenMapping() {
        Vote vote = poll(now.minusDays(2), now.minusDays(1), 0);
        PublicName name = new PublicName("Rubes", "Ruby", null);

        VoteResponse response = mapper.toResponse(vote, List.of(), name);
        VoteResultResponse results = mapper.toResultResponse(vote, List.of(), name);

        assertThat(List.of(response.getCreatorDisplayName(), response.getCreatorFirstName())).containsExactly("Rubes", "Ruby");
        assertThat(response.getCreatorLastName()).isNull();
        assertThat(List.of(results.getCreatorDisplayName(), results.getCreatorFirstName())).containsExactly("Rubes", "Ruby");
        assertThat(results.getCreatorLastName()).isNull();
    }

    @Test
    @DisplayName("should carry a voter's public names")
    void shouldSetVoterNamesWhenMapping() {
        Vote vote = poll(now.minusDays(2), now.minusDays(1), 1);
        VoteOption option = option("A", 1);
        User voter = User.builder().id(UUID.randomUUID()).username("kai").build();

        var response = mapper.toVoterResponse(new com.vomatt.entity.UserVote(voter, vote, option), true,
                new PublicName("Kai", null, null));

        assertThat(response.displayName()).isEqualTo("Kai");
        assertThat(response.firstName()).isNull();
    }
}
