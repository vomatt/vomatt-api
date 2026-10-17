package com.vomatt.repository;

import org.springframework.stereotype.Component;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/**
 * The row-lock wait limit for the current transaction. Lock waits after {@link #apply()} fail after 3 s (SQLState 55P03,
 * PessimisticLockingFailureException), so a waiter gives up instead of holding a pooled connection. Hibernate ignores a
 * positive jakarta.persistence.lock.timeout hint on Postgres (no FOR UPDATE WAIT n), so it is set per transaction.
 */
@Component
@RequiredArgsConstructor
public class LockTimeout {

    private final EntityManager em;

    public void apply() {
        em.createNativeQuery("SELECT set_config('lock_timeout', '3s', true)").getSingleResult();
    }

    /** Back to the server default for the rest of the transaction, so later statements are not cut short. */
    public void reset() {
        em.createNativeQuery("SELECT set_config('lock_timeout', reset_val, true) FROM pg_settings WHERE name = 'lock_timeout'")
                .getSingleResult();
    }
}
