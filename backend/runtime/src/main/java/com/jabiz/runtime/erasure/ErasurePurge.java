package com.jabiz.runtime.erasure;

import com.jabiz.entity.Violation;
import com.jabiz.event.DomainEvent;
import com.jabiz.event.EventSubscription;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.job.JobDefinition;
import com.jabiz.job.JobItems;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.BoundValue;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.runtime.storage.MaintenanceStorage;
import com.jabiz.runtime.storage.Rows;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/**
 * The purge of an erasure (docs/design/10-security.md section 17; decision D62 item 3): what the account erasure could
 * not change through the ordinary write path - the history of the user's rows and the append-only records that copied
 * its data - is overwritten on the maintenance connection ({@link MaintenanceStorage}) by {@link ErasurePurger}. Runs
 * for each erasure as the consumer of {@value #ERASED_EVENT}; a purge that fails is retried with the event, and a purge
 * already done does nothing. Without a maintenance connection it refuses (422 {@code ERASURE_PURGE_UNAVAILABLE}).
 */
@Configuration
public class ErasurePurge {

    public static final String NAME = "ERASURE_PURGE";
    /** Published by the account erasure, with {@code erasureId} and {@code userId}. */
    public static final String ERASED_EVENT = "jabiz.account.erased";
    static final String CONSUMER = "jabiz.erasure-purge";
    /** The job that takes up the erasures not purged yet, and its selection. */
    public static final String JOB = "jabiz.erasure-purge";
    static final String PENDING = "jabiz.erasure_pending";

    /** @param erasureId the erasure to purge */
    public record PurgeInput(@NotNull UUID erasureId) {}

    /**
     * @param purged   whether this run purged (false: it was purged before)
     * @param rowCount rows overwritten
     */
    public record PurgeOutput(String erasureId, boolean purged, int rowCount) {}

    static final String INPUT = "input";
    static final String OUTPUT = "output";

    public static final ProcessDefinition<PurgeInput, PurgeOutput, ProcessContext> PURGE =
        ProcessDefinition.define(NAME, 1, PurgeInput.class, PurgeOutput.class, ProcessContext.class, pb -> pb
            .description("Overwrites the personal data an erased account left in history and append-only records.")
            .permissions(ErasurePermissions.ERASE)
            .internal()
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> ctx.get(OUTPUT, PurgeOutput.class))
            .step("Purge the erasure", PurgeStep.class, new PurgeStep.Metadata()));

    @Bean
    ProcessDefinition<PurgeInput, PurgeOutput, ProcessContext> erasurePurgeProcess() {
        return PURGE;
    }

    @Bean
    EventSubscription<PurgeInput> erasurePurgeSubscription(
        ProcessDefinition<PurgeInput, PurgeOutput, ProcessContext> erasurePurgeProcess) {
        return EventSubscription.of(CONSUMER, ERASED_EVENT, erasurePurgeProcess, ErasurePurge::input);
    }

    /**
     * The purges still to do, once a day (decision D62 item 3): an erasure whose event was given up on - no
     * maintenance connection yet, a failure past the retries - is purged once the cause is gone.
     */
    @Bean
    JobDefinition<PurgeInput> erasurePurgeJob(
        ProcessDefinition<PurgeInput, PurgeOutput, ProcessContext> erasurePurgeProcess,
        @Value("${jabiz.erasure.purge.cron:0 23 3 * * *}") String cron) {
        return JobDefinition.forEach(JOB, cron, ZoneOffset.UTC, JobItems.template(PENDING, "erasureId"),
            erasurePurgeProcess, (scheduledTime, row) -> new PurgeInput(Rows.uuid(row.get("erasureId"))));
    }

    private static PurgeInput input(DomainEvent event) {
        return new PurgeInput(UUID.fromString(String.valueOf(event.payload().get("erasureId"))));
    }

    /**
     * The maintenance connection, when configured: {@code jabiz.erasure.datasource.url} (R2DBC, the application's
     * database and schema), {@code .username} and {@code .password} of a login that is a member of
     * {@code jabiz_maintenance}; from the environment only ({@code JABIZ_ERASURE_DATASOURCE_*}). A URL that is not
     * R2DBC (a JDBC one, typically) makes no connection rather than failing the context with a stack trace: the
     * startup check ({@link AccountDeletionChecks}) reports it in a line, with every other problem at once.
     */
    @Bean
    @ConditionalOnProperty(name = "jabiz.erasure.datasource.url")
    MaintenanceStorage erasureMaintenanceStorage(@Value("${jabiz.erasure.datasource.url}") String url,
        @Value("${jabiz.erasure.datasource.username:}") String username,
        @Value("${jabiz.erasure.datasource.password:}") String password) {
        return MaintenanceStorage.urlProblem(url) == null ? MaintenanceStorage.connect(url, username, password) : null;
    }

    /** The step: reads the erasure, purges it unless purged already. */
    @Component
    public static class PurgeStep implements StepHandler<PurgeStep.Metadata, ProcessContext> {

        public record Metadata() {}

        private final ObjectProvider<MaintenanceStorage> maintenance;
        private final ErasurePurger purger;
        private final StorageAdapterRegistry storages;
        private final String poolRef;

        public PurgeStep(ObjectProvider<MaintenanceStorage> maintenance, ErasurePurger purger,
            StorageAdapterRegistry storages, @Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
            this.maintenance = maintenance;
            this.purger = purger;
            this.storages = storages;
            this.poolRef = poolRef;
        }

        @Override
        public Mono<Void> execute(Metadata metadata, ProcessContext ctx) {
            UUID erasureId = ctx.get(INPUT, PurgeInput.class).erasureId();
            return storages.getEngine(poolRef).select("SELECT e.user_id, p.erasure_id AS purged FROM sys_erasure e"
                    + " LEFT JOIN sys_erasure_purge p ON p.erasure_id = e.erasure_id WHERE e.erasure_id = :id",
                    Map.of("id", BoundValue.of(erasureId)))
                .next()
                .switchIfEmpty(Mono.error(() -> new EntityNotFoundException("No erasure " + erasureId)))
                .flatMap(row -> {
                    if (row.get("purged") != null) {
                        ctx.put(OUTPUT, new PurgeOutput(erasureId.toString(), false, 0));
                        return Mono.empty();
                    }
                    MaintenanceStorage storage = maintenance.getIfAvailable();
                    if (storage == null) {
                        ctx.reject(new Violation(null, PlatformErrorCodes.ERASURE_PURGE_UNAVAILABLE, "No maintenance"
                            + " connection is configured (jabiz.erasure.datasource.*)"));
                        return Mono.empty();
                    }
                    UUID userId = Rows.uuid(row.get("user_id"));
                    return storage.inPurgeTransaction(engine -> purger.purge(engine, erasureId, userId,
                            ctx.processSeqId(), ctx.opTime()))
                        .doOnNext(count -> ctx.put(OUTPUT, new PurgeOutput(erasureId.toString(), true, count)))
                        .then();
                });
        }
    }
}
