package com.vomatt.votes;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.vomatt.common.redis.RedisService;
import com.vomatt.repository.VoteRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Counts Views off the request path (see {@link VoteService#getVote}): a Redis or database failure, or a slow Redis,
 * never reaches the reader. Best effort: a View that cannot be counted is dropped, and its dedupe key with it, so the
 * viewer's next visit counts.
 */
@Service
@Slf4j
public class VoteViewRecorder {

    static final String DEDUPE_NS = "vote:view";
    static final Duration DEDUPE_WINDOW = Duration.ofDays(1);

    private final RedisService redisService;
    private final VoteRepository voteRepository;
    // Own transaction even if a caller ever holds one, so a failed increment cannot mark the caller's rollback-only
    private final TransactionTemplate requiresNew;
    // @Async runs on unbounded virtual threads; cap concurrent increments so a hot Poll's row lock cannot drain the pool
    final Semaphore writePermits = new Semaphore(2);
    long permitWaitMillis = 2000;

    public VoteViewRecorder(RedisService redisService, VoteRepository voteRepository, PlatformTransactionManager txManager) {
        this.redisService = redisService;
        this.voteRepository = voteRepository;
        this.requiresNew = new TransactionTemplate(txManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Counts {@code userId}'s View of the Poll once per {@link #DEDUPE_WINDOW}. */
    @Async
    public void record(UUID voteId, UUID userId) {
        // User first, so account deletion can drop all of a user's keys by prefix
        String key = userId + ":" + voteId;
        try {
            if (!redisService.setIfAbsent(DEDUPE_NS, key, 1, DEDUPE_WINDOW)) {
                return;
            }
        } catch (RuntimeException e) {
            log.warn("View of vote {} not recorded: {}", voteId, e.getMessage());
            return;
        }
        try {
            increment(voteId);
        } catch (RuntimeException | InterruptedException e) {
            log.warn("View of vote {} not recorded: {}", voteId, e.getMessage());
            // Before restoring the interrupt flag: the Redis client fails fast on an interrupted thread
            forget(key);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Drops a deleted account's dedupe keys, which pair its id with the Polls it opened. */
    public void forgetViewer(String userId) {
        // ponytail: SCAN over the whole keyspace per account deletion; keep a per-user key set if Redis grows large
        try {
            redisService.deleteByPrefix(DEDUPE_NS, userId + ":");
        } catch (RuntimeException e) {
            log.warn("View dedupe keys of user {} not removed, they expire within {}: {}", userId, DEDUPE_WINDOW, e.getMessage());
        }
    }

    private void increment(UUID voteId) throws InterruptedException {
        if (!writePermits.tryAcquire(permitWaitMillis, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("view write permits exhausted");
        }
        try {
            requiresNew.executeWithoutResult(s -> voteRepository.incrementViewHour(voteId));
        } finally {
            writePermits.release();
        }
    }

    private void forget(String key) {
        try {
            redisService.delete(DEDUPE_NS, key);
        } catch (RuntimeException e) {
            log.warn("View dedupe key {} not removed: {}", key, e.getMessage());
        }
    }
}
