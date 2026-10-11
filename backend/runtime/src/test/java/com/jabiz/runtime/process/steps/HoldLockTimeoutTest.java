package com.jabiz.runtime.process.steps;

import com.jabiz.process.ProcessContext;
import com.jabiz.runtime.process.ExecutionOptions;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The wait of a {@code HoldLock} step is the shortest of the global, the step's and the execution's (D42). */
class HoldLockTimeoutTest {

    private static final Duration GLOBAL = Duration.ofSeconds(30);

    @Test
    void theShortestWaitApplies() {
        assertThat(HoldLock.timeout(GLOBAL, null, null)).isEqualTo("30000ms");
        assertThat(HoldLock.timeout(GLOBAL, Duration.ofSeconds(2), null)).isEqualTo("2000ms");
        assertThat(HoldLock.timeout(GLOBAL, null, Duration.ofMillis(500))).isEqualTo("500ms");
        assertThat(HoldLock.timeout(GLOBAL, Duration.ofSeconds(2), Duration.ofSeconds(5))).isEqualTo("2000ms");
        assertThat(HoldLock.timeout(GLOBAL, Duration.ofSeconds(5), Duration.ofSeconds(2))).isEqualTo("2000ms");
        // Longer than the global timeout: the global one still bounds it.
        assertThat(HoldLock.timeout(GLOBAL, Duration.ofMinutes(5), Duration.ofMinutes(5))).isEqualTo("30000ms");
        // Never 0, which PostgreSQL takes for no limit.
        assertThat(HoldLock.timeout(GLOBAL, Duration.ofNanos(10), null)).isEqualTo("1ms");
    }

    @Test
    void waitsAreDeclaredPositive() {
        assertThat(HoldLock.<ProcessContext>exclusive(ctx -> "a", Duration.ofSeconds(1)).metadata().waitAtMost())
            .isEqualTo(Duration.ofSeconds(1));
        assertThat(HoldLock.<ProcessContext>shared(ctx -> "a", Duration.ofSeconds(1)).metadata().shared()).isTrue();
        assertThat(HoldLock.<ProcessContext>shared(ctx -> "a").metadata().waitAtMost()).isNull();
        assertThatThrownBy(() -> HoldLock.<ProcessContext>exclusive(ctx -> "a", Duration.ZERO))
            .hasMessageContaining("positive");
        assertThatThrownBy(() -> HoldLock.<ProcessContext>exclusive(ctx -> "a", null))
            .isInstanceOf(NullPointerException.class);
        assertThat(ExecutionOptions.idempotent("k").withLockWait(Duration.ofSeconds(2)).lockWait())
            .isEqualTo(Duration.ofSeconds(2));
        assertThat(ExecutionOptions.idempotent("k").withLockWait(Duration.ofSeconds(2))
            .withBeforeSteps(operation -> reactor.core.publisher.Mono.empty()).lockWait())
            .isEqualTo(Duration.ofSeconds(2));
        assertThatThrownBy(() -> ExecutionOptions.NONE.withLockWait(Duration.ZERO)).hasMessageContaining("positive");
    }
}
