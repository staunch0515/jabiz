package com.jabiz.security;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Failed-login counting and locking (docs/design/10-security.md section 4). Pure: the state lives in the login
 * records, each of which carries the counter and lock that were in force after it, so only the latest record of a
 * user is needed to decide the next one.
 *
 * <p>A wrong password increments the counter; reaching {@code maxFailures} locks the account for
 * {@code lockDuration}. While locked every attempt is refused (and recorded as {@link LoginOutcome#LOCKED} without
 * extending the lock). A successful sign-in or an unlock resets the counter; so does the expiry of a lock, which gives
 * the user a fresh series of attempts.
 *
 * @param maxFailures  consecutive wrong passwords that lock the account
 * @param lockDuration how long a lock lasts
 */
public record LoginAttemptPolicy(int maxFailures, Duration lockDuration) {

    public LoginAttemptPolicy {
        if (maxFailures < 1) {
            throw new IllegalArgumentException("maxFailures must be at least 1");
        }
        Objects.requireNonNull(lockDuration, "lockDuration must not be null");
        if (lockDuration.isNegative() || lockDuration.isZero()) {
            throw new IllegalArgumentException("lockDuration must be positive");
        }
    }

    /**
     * The counters a login record carries.
     *
     * @param attemptNo    number of the record among the user's records, from 1; unique per user, so that concurrent
     *                     attempts cannot both build on the same predecessor
     * @param failureCount consecutive wrong passwords so far
     * @param lockedUntil  end of the lock in force, or null
     */
    public record State(long attemptNo, int failureCount, Instant lockedUntil) {

        /** Before the first record of a user. */
        public static final State INITIAL = new State(0, 0, null);

        public State {
            if (attemptNo < 0 || failureCount < 0) {
                throw new IllegalArgumentException("attemptNo and failureCount must not be negative");
            }
        }
    }

    /** Whether the account is locked at {@code now}, given the user's latest record. */
    public boolean isLocked(State latest, Instant now) {
        return latest != null && latest.lockedUntil() != null && now.isBefore(latest.lockedUntil());
    }

    /**
     * The state after an attempt (or an unlock) at {@code now}.
     *
     * @param latest  state of the user's latest record, or null (or {@link State#INITIAL}) for none
     * @param outcome what the attempt came to; {@link LoginOutcome#LOCKED} exactly when {@link #isLocked} held
     */
    public State next(State latest, LoginOutcome outcome, Instant now) {
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(now, "now must not be null");
        State previous = latest == null ? State.INITIAL : latest;
        long attemptNo = previous.attemptNo() + 1;
        boolean lockExpired = previous.lockedUntil() != null && !now.isBefore(previous.lockedUntil());
        int carried = lockExpired ? 0 : previous.failureCount();
        return switch (outcome) {
            case SUCCESS, UNLOCKED -> new State(attemptNo, 0, null);
            case LOCKED -> {
                if (!isLocked(previous, now)) {
                    throw new IllegalArgumentException("The account is not locked at " + now);
                }
                yield new State(attemptNo, previous.failureCount(), previous.lockedUntil());
            }
            case BAD_CREDENTIALS -> {
                int failures = carried + 1;
                yield new State(attemptNo, failures, failures >= maxFailures ? now.plus(lockDuration) : null);
            }
            // The password was right, so nothing is added; the refusal has other causes.
            case DISABLED, NO_ROLE -> new State(attemptNo, carried, null);
        };
    }
}
