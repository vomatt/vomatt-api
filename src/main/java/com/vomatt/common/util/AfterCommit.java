package com.vomatt.common.util;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Side effects that must only happen once the database change is committed (and never after a rollback). */
public final class AfterCommit {

    private AfterCommit() {}

    /** Runs {@code action} after the current transaction commits, or right away when there is none. */
    public static void run(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
