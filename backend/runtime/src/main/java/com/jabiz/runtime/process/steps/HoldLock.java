package com.jabiz.runtime.process.steps;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSpec;
import com.jabiz.query.BoundValue;
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
 * may wait for each other (the database then fails one of them). A process waits at most
 * {@code jabiz.process.lock-timeout} (30 seconds by default); a deadlock or a wait that long fails it with
 * {@link com.jabiz.runtime.ConcurrentUpdateException} (409). In-transaction steps only: after the commit there is no
 * transaction to hold it.
 */
@Component
public class HoldLock<C extends ProcessContext> implements StepHandler<HoldLock.Metadata<C>, C> {

    /** @param name the lock's name from the context; null or blank takes no lock */
    public record Metadata<C>(Function<C, String> name, boolean shared) {
        public Metadata {
            Objects.requireNonNull(name, "name must not be null");
        }
    }

    /** A lock others may hold at the same time with {@code shared}, but not with {@link #exclusive}. */
    public static <C extends ProcessContext> StepSpec<Metadata<C>, C> shared(Function<C, String> name) {
        return StepSpec.of(HoldLock.class, new Metadata<>(name, true));
    }

    /** A lock nobody else holds, shared or exclusive, at the same time. */
    public static <C extends ProcessContext> StepSpec<Metadata<C>, C> exclusive(Function<C, String> name) {
        return StepSpec.of(HoldLock.class, new Metadata<>(name, false));
    }

    /** Prefix that keeps the applications' names apart from the platform's own lock keys. */
    static final String NAMESPACE = "jabiz.app-lock:";

    private final StorageAdapterRegistry storages;
    private final String poolRef;
    private final String timeout;

    public HoldLock(StorageAdapterRegistry storages, @Value("${jabiz.storage.default-pool-ref:default}") String poolRef,
        @Value("${jabiz.process.lock-timeout:30s}") Duration timeout) {
        this.storages = storages;
        this.poolRef = poolRef;
        this.timeout = Math.max(1, timeout.toMillis()) + "ms";
    }

    @Override
    public Mono<Void> execute(Metadata<C> metadata, C ctx) {
        return Mono.defer(() -> {
            String name = metadata.name().apply(ctx);
            if (name == null || name.isBlank()) {
                return Mono.empty();
            }
            // The wait is bounded for this statement only: the transaction's own lock_timeout is put back after it.
            var engine = storages.getEngine(poolRef);
            return engine.select("SELECT current_setting('lock_timeout') AS previous", Map.of()).single()
                .flatMap(row -> engine.select("SELECT set_config('lock_timeout', :timeout, true) AS t",
                        Map.of("timeout", BoundValue.of(timeout))).then()
                    .then(engine.select("SELECT 1 AS locked FROM (SELECT pg_advisory_xact_lock"
                        + (metadata.shared() ? "_shared" : "") + "(hashtextextended(:key, 0))) l",
                        Map.of("key", BoundValue.of(NAMESPACE + name))).then())
                    .then(engine.select("SELECT set_config('lock_timeout', :previous, true) AS t",
                        Map.of("previous", BoundValue.of((String) row.get("previous")))).then()));
        });
    }
}
