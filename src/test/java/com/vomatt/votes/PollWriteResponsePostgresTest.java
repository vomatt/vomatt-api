package com.vomatt.votes;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import com.vomatt.entity.Audience;
import com.vomatt.entity.Tag;
import com.vomatt.entity.User;
import com.vomatt.entity.Vote;
import com.vomatt.repository.PostgresRepositoryTest;
import com.vomatt.votes.dto.CreateVoteRequest;
import com.vomatt.votes.dto.VoteResponse;

/** Create / Edit / Close answer with the Poll as a later read of the same Poll shows it. */
@Import(VoteServiceSlice.class)
@DisplayName("Poll write responses (Postgres)")
class PollWriteResponsePostgresTest extends PostgresRepositoryTest {

    @Autowired VoteService voteService;

    private final OffsetDateTime now = OffsetDateTime.now();
    private User owner;
    private String ownerId;
    private Tag tag;

    @BeforeEach
    void setUp() {
        owner = persistUser("pwr-" + System.nanoTime());
        ownerId = owner.getId().toString();
        tag = new Tag("t" + System.nanoTime() % 1_000_000_000, "slug-" + System.nanoTime(), null, 0);
        em.persist(tag);
        em.flush();
    }

    private CreateVoteRequest request(String title, String... options) {
        CreateVoteRequest request = new CreateVoteRequest();
        request.setTitle(title);
        // Distinct display orders: options tied on it come back in no fixed order
        request.setOptions(IntStream.range(0, options.length).mapToObj(i -> {
            CreateVoteRequest.VoteOptionRequest option = new CreateVoteRequest.VoteOptionRequest();
            option.setText(options[i]);
            option.setDisplayOrder(i);
            return option;
        }).toList());
        request.setStartTime(now.plusHours(1));
        request.setEndTime(now.plusDays(1));
        request.setTagIds(List.of(tag.getId()));
        return request;
    }

    // Compared with a fresh read in a cleared persistence context. Times only to the database's microseconds; the tag
    // usage count is a counter bumped by a bulk UPDATE, so the write's own response does not see its own +1.
    private void assertMatchesFreshRead(VoteResponse written) {
        em.flush();
        em.clear();
        VoteResponse read = voteService.getVote(written.getId(), ownerId);
        assertThat(written).usingRecursiveComparison()
                .withComparatorForType((a, b) -> Duration.between(a, b).abs().toMillis() < 1 ? 0 : a.compareTo(b),
                        OffsetDateTime.class)
                .ignoringFields("tags.usageCount")
                .isEqualTo(read);
    }

    @Test
    @DisplayName("should answer with the stored Poll when creating it")
    void shouldMatchFreshReadWhenCreating() {
        assertMatchesFreshRead(voteService.createVote(request("created", "A", "B"), ownerId));
    }

    @Test
    @DisplayName("should answer with the stored Poll, new update time included, when editing it")
    void shouldMatchFreshReadWhenEditing() {
        VoteResponse created = voteService.createVote(request("created", "A", "B"), ownerId);
        em.flush();
        em.clear();

        VoteResponse edited = voteService.updateVote(created.getId(), request("edited", "C", "D", "E"), ownerId);

        assertThat(edited.getUpdatedAt()).isAfter(created.getUpdatedAt());
        assertMatchesFreshRead(edited);
    }

    @Test
    @DisplayName("should answer with the stored Poll, new update time included, when closing it")
    void shouldMatchFreshReadWhenClosing() {
        Vote open = persistPoll(owner, now.minusHours(1), now.plusDays(1), "A", "B");
        open.addTag(tag);
        // Only a non-Public Poll can be Closed
        open.setAudience(Audience.UNLISTED);
        em.flush();
        em.clear();

        VoteResponse closed = voteService.closeVote(open.getId().toString(), ownerId);

        assertThat(closed.getUpdatedAt()).isAfter(open.getUpdatedAt());
        assertMatchesFreshRead(closed);
    }
}
