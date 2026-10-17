package com.jabiz.runtime.storage;

import java.util.Objects;

/**
 * The {@code lock_timeout} the executor set for the running process transaction (docs/design/06-process.md section
 * 4.1), in the Reactor context of its steps: a {@code HoldLock} that would wait just as long takes its lock in one
 * statement, without setting and restoring the timeout (ROADMAP phase 16p, T1).
 *
 * @param setting the value as set, such as {@code 30000ms}
 */
public record TransactionLockTimeout(String setting) {
    // A transaction of its own (StorageEngine#inNewTransaction) runs without it: its timeout is not this one.
    public TransactionLockTimeout {
        Objects.requireNonNull(setting, "setting must not be null");
    }
}
