package com.jabiz.runtime;

/**
 * The database gave up waiting for a lock: the wait exceeded {@code lock_timeout} (SQLSTATE {@code 55P03}) or it found
 * a deadlock and chose this transaction ({@code 40P01}). Always {@linkplain #busy() busy}; answered with 409.
 */
public class LockNotAvailableException extends ConcurrentUpdateException {

    public static final String LOCK_TIMEOUT = "55P03";
    public static final String DEADLOCK = "40P01";

    private final String sqlState;

    public LockNotAvailableException(String sqlState, Throwable cause) {
        super(DEADLOCK.equals(sqlState) ? "A deadlock with another operation; try again"
            : "Another operation holds what this one needs; try again", cause, true);
        this.sqlState = sqlState;
    }

    /** {@value #LOCK_TIMEOUT} or {@value #DEADLOCK}. */
    public String sqlState() {
        return sqlState;
    }

    public boolean deadlock() {
        return DEADLOCK.equals(sqlState);
    }
}
