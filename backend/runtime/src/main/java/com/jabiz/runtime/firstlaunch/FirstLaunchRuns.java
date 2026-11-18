package com.jabiz.runtime.firstlaunch;

import com.jabiz.query.BoundValue;
import com.jabiz.runtime.storage.Rows;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.runtime.storage.StorageEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads the first launch's record (docs/design/23-first-launch.md section 4): the run, if there is one, and its
 * current state; whether the database has ever had a user. Writes go through the platform's processes
 * ({@code FIRST_LAUNCH_START}, {@code FIRST_LAUNCH_RECORD}).
 */
@Component
public class FirstLaunchRuns {

    static final String RUN = "sys_first_launch_run";
    static final String EVENT = "sys_first_launch_event";

    private final StorageAdapterRegistry storages;
    private final String poolRef;

    public FirstLaunchRuns(StorageAdapterRegistry storages,
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.storages = storages;
        this.poolRef = poolRef;
    }

    /** The run, if the first launch ever began on this database. */
    public Mono<Optional<FirstLaunchRun>> current() {
        return engine().select("SELECT r.run_id, r.config_hash, r.started_time, e.kind, e.event_time, e.step_key,"
                + " e.reason FROM " + RUN + " r LEFT JOIN LATERAL (SELECT kind, event_time, step_key, reason FROM "
                + EVENT + " e WHERE e.run_id = r.run_id AND e.kind <> 'REVISED' ORDER BY e.process_seq_id DESC"
                + " LIMIT 1) e ON true WHERE r.run_slot = 1", Map.of())
            .next()
            .map(row -> {
                String kind = Rows.string(row.get("kind"));
                FirstLaunchRun.State state = kind == null ? FirstLaunchRun.State.RUNNING
                    : FirstLaunchRun.State.valueOf(kind);
                return Optional.of(new FirstLaunchRun(Rows.uuid(row.get("run_id")),
                    Rows.string(row.get("config_hash")), Rows.instant(row.get("started_time")), state,
                    kind == null ? Rows.instant(row.get("started_time")) : Rows.instant(row.get("event_time")),
                    state == FirstLaunchRun.State.FAILED ? Rows.string(row.get("step_key")) : null,
                    state == FirstLaunchRun.State.FAILED ? Rows.string(row.get("reason")) : null));
            })
            .defaultIfEmpty(Optional.empty());
    }

    /**
     * How many users the database has ever had: versions of users, tombstones included (a database whose users were
     * all deleted is not new).
     */
    public Mono<Long> usersEver() {
        return engine().select("SELECT count(DISTINCT user_id) AS users FROM sec_user_version", Map.of())
            .next().map(row -> Rows.longValue(row.get("users"))).defaultIfEmpty(0L);
    }

    /** The details recorded with the latest event of {@code kind} of the run, as JSON text (for the waiting phase). */
    public Mono<Optional<String>> detail(UUID runId, String kind) {
        return engine().select("SELECT detail::text AS detail FROM " + EVENT + " WHERE run_id = :run AND kind = :kind"
                + " ORDER BY process_seq_id DESC LIMIT 1",
                Map.of("run", BoundValue.of(runId), "kind", BoundValue.of(kind)))
            .next().map(row -> Optional.ofNullable(Rows.string(row.get("detail"))))
            .defaultIfEmpty(Optional.empty());
    }

    private StorageEngine engine() {
        return storages.getEngine(poolRef);
    }
}
