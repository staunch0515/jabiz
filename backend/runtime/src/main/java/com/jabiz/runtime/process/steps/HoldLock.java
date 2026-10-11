package com.jabiz.runtime.process.steps;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSpec;
import com.jabiz.query.BoundValue;
import com.jabiz.runtime.process.ExecutionOptions;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Holds a named lock until the process's transaction ends (docs/design/06-process.md section 4.1): processes that
 * must not overlap take the same name, shared by those that may run side by side, exclusive by the one that may not
 * (postings into a period share it, closing the period takes it alone). A process waits for a conflicting holder to
 * commit or roll back and then reads what it left. Names are the application's own: they never meet the platform's
 * locks. Take it before reading what the lock protects, and take several in one order everywhere, or two processes
 * may wait for each other (the database then fails one of them).
 *
 * <p>A process waits at most the shortest of {@code jabiz.process.lock-timeout} (30 seconds by default), the step's
 * own {@code waitAtMost} and the execution's {@link ExecutionOptions#lockWait()} (a job's items, decision D42); a
 * deadlock or a wait that long fails it with {@link com.jabiz.runtime.ConcurrentUpdateException} (409; a busy item of
 * a job). The bound applies to the lock statement only: the transaction's own {@code lock_timeout} is put back after
 * it. In-transaction steps only: after the commit there is no transaction to hold it.
 */
@Component
public class HoldLock<C extends ProcessContext> implements StepHandler<HoldLock.Metadata<C>, C> {

    /**
     * @param name       the lock's name from the context; null or blank takes no lock
     * @param waitAtMost the longest this step waits, below the global timeout; null for the global timeout
     */
    public record Metadata<C>(Function<C, String> name, boolean shared, Duration waitAtMost) {
        public Metadata {
            Objects.requireNonNull(name, "name must not be null");
            if (waitAtMost != null && (waitAtMost.isNegative() || waitAtMost.isZero())) {
                throw new IllegalArgumentException("waitAtMost must be positive");
            }
        }

        public Metadata(Function<C, String> name, boolean shared) {
            this(name, shared, null);
        }
    }

    /** A lock others may hold at the same time with {@code shared}, but not with {@link #exclusive}. */
    public static <C extends ProcessContext> StepSpec<Metadata<C>, C> shared(Function<C, String> name) {
        return StepSpec.of(HoldLock.class, new Metadata<>(name, true));
    }

    /** As {@link #shared(Function)}, waiting at most {@code waitAtMost} for it (a 409 after that). */
    public static <C extends ProcessContext> StepSpec<Metadata<C>, C> shared(Function<C, String> name,
        Duration waitAtMost) {
        return StepSpec.of(HoldLock.class, new Metadata<>(name, true,
            Objects.requireNonNull(waitAtMost, "waitAtMost must not be null")));
    }

    /** A lock nobody else holds, shared or exclusive, at the same time. */
    public static <C extends ProcessContext> StepSpec<Metadata<C>, C> exclusive(Function<C, String> name) {
        return StepSpec.of(HoldLock.class, new Metadata<>(name, false));
    }

    /** As {@link #exclusive(Function)}, waiting at most {@code waitAtMost} for it (a 409 after that). */
    public static <C extends ProcessContext> StepSpec<Metadata<C>, C> exclusive(Function<C, String> name,
        Duration waitAtMost) {
        return StepSpec.of(HoldLock.class, new Metadata<>(name, false,
            Objects.requireNonNull(waitAtMost, "waitAtMost must not be null")));
    }

    /** Prefix that keeps the applications' names apart from the platform's own lock keys. */
    static final String NAMESPACE = "jabiz.app-lock:";

    private final StorageAdapterRegistry storages;
    private final String poolRef;
    private final Duration timeout;

    public HoldLock(StorageAdapterRegistry storages, @Value("${jabiz.storage.default-pool-ref:default}") String poolRef,
        @Value("${jabiz.process.lock-timeout:30s}") Duration timeout) {
        this.storages = storages;
        this.poolRef = poolRef;
        this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
    }

    /**
     * The {@code lock_timeout} of the lock statement: the shortest of the global timeout, the step's and the
     * execution's wait, and never 0 (which PostgreSQL takes for no limit).
     */
    static String timeout(Duration global, Duration step, Duration execution) {
        Duration wait = global;
        if (step != null && step.compareTo(wait) < 0) {
            wait = step;
        }
        if (execution != null && execution.compareTo(wait) < 0) {
            wait = execution;
        }
        return Math.max(1, wait.toMillis()) + "ms";
    }

    @Override
    public Mono<Void> execute(Metadata<C> metadata, C ctx) {
        return Mono.deferContextual(view -> {
            String name = metadata.name().apply(ctx);
            if (name == null || name.isBlank()) {
                return Mono.empty();
            }
            ExecutionOptions.LockWait execution = view.getOrDefault(ExecutionOptions.LockWait.class, null);
            String wait = timeout(timeout, metadata.waitAtMost(), execution == null ? null : execution.atMost());
            // The wait is bounded for this statement only: the transaction's own lock_timeout is put back after it.
            var engine = storages.getEngine(poolRef);
            return engine.select("SELECT current_setting('lock_timeout') AS previous", Map.of()).single()
                .flatMap(row -> engine.select("SELECT set_config('lock_timeout', :timeout, true) AS t",
                        Map.of("timeout", BoundValue.of(wait))).then()
                    .then(engine.select("SELECT 1 AS locked FROM (SELECT pg_advisory_xact_lock"
                        + (metadata.shared() ? "_shared" : "") + "(hashtextextended(:key, 0))) l",
                        Map.of("key", BoundValue.of(NAMESPACE + name))).then())
                    .then(engine.select("SELECT set_config('lock_timeout', :previous, true) AS t",
                        Map.of("previous", BoundValue.of((String) row.get("previous")))).then()));
        });
    }
}
