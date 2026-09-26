package com.jabiz.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginAttemptPolicyTest {

    private static final Instant T0 = Instant.parse("2026-01-31T09:00:00Z");
    private final LoginAttemptPolicy policy = new LoginAttemptPolicy(3, Duration.ofMinutes(15));

    private LoginAttemptPolicy.State fail(LoginAttemptPolicy.State state, Instant at) {
        return policy.next(state, LoginOutcome.BAD_CREDENTIALS, at);
    }

    @Test
    void consecutiveFailuresLockTheAccountForTheLockDuration() {
        LoginAttemptPolicy.State one = fail(null, T0);
        LoginAttemptPolicy.State two = fail(one, T0.plusSeconds(1));
        assertThat(policy.isLocked(two, T0.plusSeconds(2))).isFalse();
        assertThat(two).isEqualTo(new LoginAttemptPolicy.State(2, 2, null));

        LoginAttemptPolicy.State three = fail(two, T0.plusSeconds(2));
        assertThat(three.failureCount()).isEqualTo(3);
        assertThat(three.lockedUntil()).isEqualTo(T0.plusSeconds(2).plus(Duration.ofMinutes(15)));
        assertThat(policy.isLocked(three, T0.plusSeconds(3))).isTrue();
        assertThat(policy.isLocked(three, three.lockedUntil())).isFalse();
    }

    @Test
    void attemptsWhileLockedDoNotExtendTheLock() {
        LoginAttemptPolicy.State locked = fail(fail(fail(null, T0), T0), T0);
        LoginAttemptPolicy.State again = policy.next(locked, LoginOutcome.LOCKED, T0.plusSeconds(60));

        assertThat(again.attemptNo()).isEqualTo(4);
        assertThat(again.failureCount()).isEqualTo(3);
        assertThat(again.lockedUntil()).isEqualTo(locked.lockedUntil());
    }

    @Test
    void anExpiredLockStartsAFreshSeries() {
        LoginAttemptPolicy.State locked = fail(fail(fail(null, T0), T0), T0);
        Instant later = locked.lockedUntil().plusSeconds(1);

        LoginAttemptPolicy.State next = fail(locked, later);
        assertThat(next.failureCount()).isEqualTo(1);
        assertThat(next.lockedUntil()).isNull();
        assertThat(policy.isLocked(next, later)).isFalse();
    }

    @Test
    void successAndUnlockResetTheCounter() {
        LoginAttemptPolicy.State two = fail(fail(null, T0), T0);
        assertThat(policy.next(two, LoginOutcome.SUCCESS, T0)).isEqualTo(new LoginAttemptPolicy.State(3, 0, null));

        LoginAttemptPolicy.State locked = fail(two, T0);
        LoginAttemptPolicy.State unlocked = policy.next(locked, LoginOutcome.UNLOCKED, T0.plusSeconds(1));
        assertThat(unlocked).isEqualTo(new LoginAttemptPolicy.State(4, 0, null));
        assertThat(policy.isLocked(unlocked, T0.plusSeconds(2))).isFalse();
    }

    @Test
    void refusalsWithTheRightPasswordNeitherCountNorReset() {
        LoginAttemptPolicy.State two = fail(fail(null, T0), T0);
        assertThat(policy.next(two, LoginOutcome.DISABLED, T0)).isEqualTo(new LoginAttemptPolicy.State(3, 2, null));
        assertThat(policy.next(two, LoginOutcome.NO_ROLE, T0)).isEqualTo(new LoginAttemptPolicy.State(3, 2, null));
    }

    @Test
    void lockedIsOnlyValidWhileLocked() {
        assertThatThrownBy(() -> policy.next(null, LoginOutcome.LOCKED, T0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(policy.isLocked(null, T0)).isFalse();
        assertThat(policy.isLocked(LoginAttemptPolicy.State.INITIAL, T0)).isFalse();
    }

    @Test
    void settingsAndStatesAreValidated() {
        assertThatThrownBy(() -> new LoginAttemptPolicy(0, Duration.ofMinutes(1)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoginAttemptPolicy(1, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoginAttemptPolicy(1, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LoginAttemptPolicy.State(-1, 0, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.next(null, null, T0)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void outcomeCodesAreTheEnumNames() {
        assertThat(LoginOutcome.codes()).containsExactly("SUCCESS", "BAD_CREDENTIALS", "LOCKED", "DISABLED", "NO_ROLE",
            "UNLOCKED");
    }
}
