package com.jabiz.runtime.firstlaunch;

import com.jabiz.context.RequestContext;
import com.jabiz.firstlaunch.FirstLaunchHandler;
import com.jabiz.query.BoundValue;
import com.jabiz.runtime.PermissionDeniedException;
import com.jabiz.query.SqlIdentifiers;
import com.jabiz.runtime.storage.Rows;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.runtime.storage.StorageEngine;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The checks behind the first launch's own powers (docs/design/23-first-launch.md section 6, decision D82):
 * publishing the initial values of controls, approving what the run itself submitted, and acting as an account it
 * created. Each holds only inside a step of a run whose content phase is still going, and only for what that run
 * made; once the run records {@code CONTENT_DONE} (or fails), none of them works again. "Inside a step of the run" and
 * "made by the run" are judged by the operation's lineage in {@code op_process}, not by request ids.
 */
@Component
public class FirstLaunchPowers {

    /** The actor of the first launch's decisions and publications. */
    public static final String ACTOR = "first-launch";

    private final FirstLaunchRuns runs;
    private final StorageAdapterRegistry storages;
    private final String poolRef;
    private final ObjectProvider<FirstLaunchHandler> handlers;

    public FirstLaunchPowers(FirstLaunchRuns runs, StorageAdapterRegistry storages,
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef, ObjectProvider<FirstLaunchHandler> handlers) {
        this.runs = runs;
        this.storages = storages;
        this.poolRef = poolRef;
        this.handlers = handlers;
    }

    /**
     * The run whose content phase the operation {@code processSeqId} belongs to; refused otherwise (403
     * {@code PERMISSION_DENIED}). Judged by the operation's lineage, never by its request id (which a client or a
     * relay could choose): following {@code parent_seq_id} to the top-level operation, that operation must be a
     * {@code FIRST_LAUNCH_STEP} (which only the platform's runner starts) of the run, and the run must still be in its
     * content phase.
     *
     * @param system whether the step must run as the system (publishing, approving) rather than as an account
     */
    public Mono<FirstLaunchRun> contentPhase(RequestContext request, long processSeqId, boolean system) {
        if (system != RequestContext.SYSTEM_ACTOR.equals(request.actorId())) {
            return Mono.error(denied(system ? "only the system may do this, in a step of the first launch"
                : "only an account may be acted as"));
        }
        return rootRun(processSeqId).flatMap(found -> runs.current().flatMap(run -> {
            if (found.isEmpty()) {
                return Mono.error(denied("only a step of the first launch's content phase may do this"));
            }
            return run.isPresent() && run.get().runId().toString().equals(found.get())
                && run.get().state() == FirstLaunchRun.State.RUNNING
                ? Mono.just(run.get())
                : Mono.error(denied("the first launch's content phase is over"));
        }));
    }

    /**
     * The run of the {@code FIRST_LAUNCH_STEP} at the top of the operation's lineage, if the top is one. Buffered
     * inserts of the transaction are written before the query (decision D46), so the current operation is found.
     */
    Mono<java.util.Optional<String>> rootRun(long processSeqId) {
        return engine().select("WITH RECURSIVE chain AS (SELECT process_seq_id, parent_seq_id, process_name,"
                + " input_summary FROM op_process WHERE process_seq_id = :seq UNION ALL SELECT p.process_seq_id,"
                + " p.parent_seq_id, p.process_name, p.input_summary FROM op_process p JOIN chain c"
                + " ON p.process_seq_id = c.parent_seq_id) SELECT process_name, input_summary ->> 'runId' AS run_id"
                + " FROM chain WHERE parent_seq_id IS NULL", Map.of("seq", BoundValue.of(processSeqId)))
            .next()
            .map(row -> FirstLaunchProcesses.STEP.equals(Rows.string(row.get("process_name")))
                ? java.util.Optional.ofNullable(Rows.string(row.get("run_id")))
                : java.util.Optional.<String>empty())
            .defaultIfEmpty(java.util.Optional.empty());
    }

    /**
     * Refuses unless the first version of the instance {@code id} of the temporal table was written by a step of
     * {@code run} (by lineage, as {@link #contentPhase}): the first launch decides and publishes only what it made.
     */
    public Mono<Void> requireMadeInRun(FirstLaunchRun run, String table, String idColumn, Object id) {
        String sql = "SELECT v.process_seq_id FROM " + SqlIdentifiers.require(table) + " v WHERE v."
            + SqlIdentifiers.require(idColumn) + " = :id ORDER BY v.version_no LIMIT 1";
        return engine().select(sql, Map.of("id", BoundValue.of(id instanceof String text ? UUID.fromString(text) : id)))
            .next()
            .map(row -> Rows.longValue(row.get("process_seq_id")))
            .flatMap(this::rootRun)
            .defaultIfEmpty(java.util.Optional.empty())
            .flatMap(found -> found.isPresent() && found.get().equals(run.runId().toString()) ? Mono.<Void>empty()
                : Mono.error(denied(table + " " + id + " was not made by this first launch")));
    }

    /**
     * Refuses unless the user was created by a step of {@code run} and has never had a credential: no password, no
     * second factor, no passkey, no linked identity, no session and no successful sign-in. Once the person has one, the first launch no longer acts for
     * them.
     */
    public Mono<Void> requireActable(FirstLaunchRun run, String userId) {
        UUID user;
        try {
            user = UUID.fromString(userId);
        } catch (IllegalArgumentException e) {
            return Mono.error(denied("the first launch acts only as a user it created"));
        }
        Map<String, BoundValue> params = Map.of("user", BoundValue.of(user));
        Mono<Boolean> credentials = engine().select("SELECT 1 AS found WHERE EXISTS (SELECT 1 FROM sec_user_version"
                + " WHERE user_id = :user AND password_hash IS NOT NULL) OR EXISTS (SELECT 1 FROM sec_user_mfa_version"
                + " WHERE user_id = :user) OR EXISTS (SELECT 1 FROM sec_user_passkey WHERE user_id = :user)"
                + " OR EXISTS (SELECT 1 FROM sec_user_identity_version WHERE user_id = :user)"
                // A session or a successful sign-in (an e-mail link signs in without a password) counts as well.
                + " OR EXISTS (SELECT 1 FROM sec_refresh_token WHERE user_id = :user)"
                + " OR EXISTS (SELECT 1 FROM sec_login_record_version WHERE user_id = :user AND outcome = 'SUCCESS')",
                params)
            .hasElements();
        return requireMadeInRun(run, "sec_user_version", "user_id", user)
            .then(credentials)
            .flatMap(has -> has ? Mono.error(denied("the account has a credential of its own: the first launch no"
                + " longer acts as it")) : Mono.<Void>empty());
    }

    /** The processes a step may run as an account (the application's handler declares them). */
    public Set<String> actingProcesses() {
        Set<String> processes = new LinkedHashSet<>();
        handlers.orderedStream().forEach(handler -> processes.addAll(handler.actingProcesses()));
        return Set.copyOf(processes);
    }

    /** The controls whose initial values the first launch may publish, by target key. */
    public Set<String> publishableControlKeys() {
        Set<String> keys = new LinkedHashSet<>();
        handlers.orderedStream().forEach(handler -> keys.addAll(handler.publishableControlKeys()));
        return Set.copyOf(keys);
    }

    private StorageEngine engine() {
        return storages.getEngine(poolRef);
    }

    private static PermissionDeniedException denied(String why) {
        return new PermissionDeniedException(FirstLaunchProcesses.PERMISSION, "First launch: " + why);
    }
}
