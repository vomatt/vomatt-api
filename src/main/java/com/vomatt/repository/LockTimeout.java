package com.vomatt.repository;

import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/**
 * The 3 s row-lock wait limit (SQLState 55P03, PessimisticLockingFailureException, which GlobalExceptionHandler maps to
 * 409 {@code common.conflict}), so a waiter gives up instead of holding a pooled connection. Hibernate ignores a positive
 * jakarta.persistence.lock.timeout hint on Postgres (no FOR UPDATE WAIT n), so it is set per transaction.
 */
@Component
@RequiredArgsConstructor
public class LockTimeout {

    private final EntityManager em;

    /**
     * Runs {@code lock} under the 3 s limit, then restores the server default so the caller's later, longer statements
     * wait as usual (the last-admin guard, followed by account deletion). On a lock timeout the exception propagates and
     * the transaction is aborted, so nothing is reset.
     */
    public <T> T within(Supplier<T> lock) {
        apply();
        T result = lock.get();
        reset();
        return result;
    }

    /** Every lock wait for the rest of the transaction is capped at 3 s (the Poll-row lock: Ballot, Close, Edit, Delete). */
    public void apply() {
        em.createNativeQuery("SELECT set_config('lock_timeout', '3s', true)").getSingleResult();
    }

    /** Back to the server default for the rest of the transaction, so later statements are not cut short. */
    private void reset() {
        em.createNativeQuery("SELECT set_config('lock_timeout', reset_val, true) FROM pg_settings WHERE name = 'lock_timeout'")
                .getSingleResult();
    }
}
