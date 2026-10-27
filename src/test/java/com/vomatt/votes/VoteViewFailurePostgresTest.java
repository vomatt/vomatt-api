package com.vomatt.votes;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.vomatt.common.redis.RedisService;
import com.vomatt.repository.PostgresRepositoryTest;
import com.vomatt.votes.dto.VoteResponse;

/**
 * Runs outside the test transaction, as in production (the controller holds none): recordView's increment then runs in
 * its own transaction, and its failure must neither throw nor leave anything rollback-only for the GET.
 */
@Import(VoteServiceSlice.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Poll Views without a surrounding transaction (Postgres)")
class VoteViewFailurePostgresTest extends PostgresRepositoryTest {

    @Autowired VoteService voteService;
    @Autowired RedisService redisService;

    @AfterEach
    void resetRedis() {
        reset(redisService);
    }

    @Test
    @DisplayName("should drop the View without failing when the database write fails")
    void shouldDropViewWhenIncrementFails() {
        when(redisService.setIfAbsent(anyString(), anyString(), any(), any())).thenReturn(true);
        // Unknown Poll: the increment violates the foreign key
        VoteResponse unknown = new VoteResponse();
        unknown.setId(UUID.randomUUID().toString());
        unknown.setCreatorId(UUID.randomUUID().toString());

        assertThatCode(() -> voteService.recordView(unknown, UUID.randomUUID().toString())).doesNotThrowAnyException();
    }
}
