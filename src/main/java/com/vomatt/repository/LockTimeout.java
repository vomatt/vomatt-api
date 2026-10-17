package com.vomatt.repository;

import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/**
 * The row-lock wait limit for one lock statement. Lock waits inside {@link #within} fail after 3 s (SQLState 55P03,
 * PessimisticLockingFailureException), so a waiter gives up instead of holding a pooled connection. Hibernate ignores a
 * positive jakarta.persistence.lock.timeout hint on Postgres (no FOR UPDATE WAIT n), so it is set per transaction.
 */
@Component
@RequiredArgsConstructor
public class LockTimeout {

    private final EntityManager em;

    /**
     * Runs {@code lock} (one row-lock statement) under the 3 s limit, then restores the server default so later
     * statements in the transaction wait as usual. On a lock timeout the exception propagates (GlobalExceptionHandler
     * maps it to 409 {@code common.conflict}); the transaction is aborted then, so nothing is reset.
     */
    public <T> T within(Supplier<T> lock) {
        apply();
        T result = lock.get();
        reset();
        return result;
    }

    private void apply() {
        em.createNativeQuery("SELECT set_config('lock_timeout', '3s', true)").getSingleResult();
    }

    /** Back to the server default for the rest of the transaction, so later statements are not cut short. */
    private void reset() {
        em.createNativeQuery("SELECT set_config('lock_timeout', reset_val, true) FROM pg_settings WHERE name = 'lock_timeout'")
                .getSingleResult();
    }
}
