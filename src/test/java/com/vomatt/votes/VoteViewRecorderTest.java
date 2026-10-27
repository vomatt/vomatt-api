package com.vomatt.votes;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

import com.vomatt.common.redis.RedisOperationException;
import com.vomatt.common.redis.RedisService;
import com.vomatt.repository.VoteRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("VoteViewRecorder")
class VoteViewRecorderTest {

    @Mock RedisService redisService;
    @Mock VoteRepository voteRepository;
    @Mock PlatformTransactionManager txManager;

    // Built by hand: no @Async proxy, so record runs synchronously
    private VoteViewRecorder recorder;
    private final UUID voteId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final String key = userId + ":" + voteId;

    @BeforeEach
    void setUp() {
        recorder = new VoteViewRecorder(redisService, voteRepository, txManager);
    }

    private void dedupeKeyIsNew(boolean isNew) {
        when(redisService.setIfAbsent(VoteViewRecorder.DEDUPE_NS, key, 1, VoteViewRecorder.DEDUPE_WINDOW)).thenReturn(isNew);
    }

    @Test
    @DisplayName("should increment in its own transaction when the dedupe key is new")
    void shouldIncrementInNewTransactionWhenDedupeKeyIsNew() {
        dedupeKeyIsNew(true);

        recorder.record(voteId, userId);

        verify(voteRepository).incrementViewHour(voteId);
        verify(txManager).getTransaction(argThat(
                (TransactionDefinition d) -> d.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        verify(redisService, never()).delete(anyString(), anyString());
    }

    @Test
    @DisplayName("should not increment when the viewer was counted within the window")
    void shouldNotIncrementWhenDedupeKeyExists() {
        dedupeKeyIsNew(false);

        recorder.record(voteId, userId);

        verify(voteRepository, never()).incrementViewHour(any());
    }

    @Test
    @DisplayName("should drop the View quietly when Redis is down")
    void shouldDropViewWhenRedisFails() {
        when(redisService.setIfAbsent(anyString(), anyString(), any(), any())).thenThrow(new RedisOperationException("down", null));

        recorder.record(voteId, userId);

        verify(voteRepository, never()).incrementViewHour(any());
    }

    @Test
    @DisplayName("should release the dedupe key when the increment fails, so the next visit counts")
    void shouldDeleteDedupeKeyWhenIncrementFails() {
        dedupeKeyIsNew(true);
        doThrow(new DataIntegrityViolationException("fk")).when(voteRepository).incrementViewHour(voteId);

        recorder.record(voteId, userId);

        verify(redisService).delete(VoteViewRecorder.DEDUPE_NS, key);
    }

    @Test
    @DisplayName("should release the dedupe key when no write permit frees up in time")
    void shouldDeleteDedupeKeyWhenPermitsExhausted() {
        dedupeKeyIsNew(true);
        recorder.permitWaitMillis = 0;
        recorder.writePermits.drainPermits();

        recorder.record(voteId, userId);

        verify(voteRepository, never()).incrementViewHour(any());
        verify(redisService).delete(VoteViewRecorder.DEDUPE_NS, key);
    }

    @Test
    @DisplayName("should drop a deleted account's dedupe keys by its id prefix")
    void shouldDeleteKeysByUserPrefixWhenForgettingViewer() {
        recorder.forgetViewer(userId.toString());

        verify(redisService).deleteByPrefix(VoteViewRecorder.DEDUPE_NS, userId + ":");
    }

    @Test
    @DisplayName("should not throw when Redis fails while forgetting a viewer")
    void shouldNotThrowWhenForgetViewerFails() {
        when(redisService.deleteByPrefix(anyString(), anyString())).thenThrow(new RedisOperationException("down", null));

        recorder.forgetViewer(userId.toString());
    }
}
