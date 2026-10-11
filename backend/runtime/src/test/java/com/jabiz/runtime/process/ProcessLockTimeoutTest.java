package com.jabiz.runtime.process;

import com.jabiz.runtime.ConcurrentUpdateException;
import com.jabiz.runtime.LockNotAvailableException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every process transaction bounds its lock waits (06 section 4.1): the global timeout, or a job item's shorter wait;
 * and which conflicts are only "busy" (D42).
 */
class ProcessLockTimeoutTest {

    @Test
    void theTransactionWaitsTheGlobalTimeoutOrTheExecutionsShorterOne() {
        Duration global = Duration.ofSeconds(30);
        assertThat(ProcessExecutor.lockTimeoutSetting(global, null)).isEqualTo("30000ms");
        assertThat(ProcessExecutor.lockTimeoutSetting(global, Duration.ofSeconds(2))).isEqualTo("2000ms");
        assertThat(ProcessExecutor.lockTimeoutSetting(global, Duration.ofMinutes(1))).isEqualTo("30000ms");
        assertThat(ProcessExecutor.lockTimeoutSetting(Duration.ZERO, null)).isEqualTo("1ms");
    }

    @Test
    void onlyLockFailuresAndStaleVersionsAreBusy() {
        assertThat(new LockNotAvailableException(LockNotAvailableException.LOCK_TIMEOUT, null).busy()).isTrue();
        LockNotAvailableException deadlock = new LockNotAvailableException(LockNotAvailableException.DEADLOCK, null);
        assertThat(deadlock.busy()).isTrue();
        assertThat(deadlock.deadlock()).isTrue();
        assertThat(deadlock.getMessage()).contains("deadlock");
        assertThat(ConcurrentUpdateException.stale("version conflict").busy()).isTrue();
        assertThat(new ConcurrentUpdateException("already exists").busy()).isFalse();
        assertThat(new ConcurrentUpdateException("conflict", new IllegalStateException()).busy()).isFalse();
    }
}
