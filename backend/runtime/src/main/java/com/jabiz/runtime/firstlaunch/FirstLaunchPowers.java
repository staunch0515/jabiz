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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The checks behind the first launch's own powers (docs/design/23-first-launch.md section 6, decision D82):
 * publishing the initial values of controls, approving what the run itself submitted, and acting as an account it
 * created. Each holds only inside a step of a run whose content phase is still going, and only for what that run
 * made; once the run records {@code CONTENT_DONE} (or fails), none of them works again.
 */
@Component
public class FirstLaunchPowers {

    /** The request id of a step: {@code first-launch-<run>-<step key>}. */
    private static final Pattern STEP_REQUEST = Pattern.compile(
        "first-launch-([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})-.+");

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
     * The run this request is a step of, while its content phase goes on; refused otherwise (403
     * {@code PERMISSION_DENIED}).
     *
     * @param system whether the step must run as the system (publishing, approving) rather than as an account
     */
    public Mono<FirstLaunchRun> contentPhase(RequestContext request, boolean system) {
        Matcher step = STEP_REQUEST.matcher(request.requestId() == null ? "" : request.requestId());
        if (!step.matches() || system != RequestContext.SYSTEM_ACTOR.equals(request.actorId())) {
            return Mono.error(denied("only a step of the first launch's content phase may do this"));
        }
        UUID runId = UUID.fromString(step.group(1));
        return runs.current().flatMap(run -> run.isPresent() && run.get().runId().equals(runId)
            && run.get().state() == FirstLaunchRun.State.RUNNING
            ? Mono.just(run.get())
            : Mono.error(denied("the first launch's content phase is over")));
    }

    /**
     * Refuses unless the first version of the instance {@code id} of the temporal table was written by a step of
     * {@code run}: the first launch decides and publishes only what it made itself.
     */
    public Mono<Void> requireMadeInRun(FirstLaunchRun run, String table, String idColumn, Object id) {
        String sql = "SELECT p.request_id FROM " + SqlIdentifiers.require(table) + " v JOIN op_process p"
            + " ON p.process_seq_id = v.process_seq_id WHERE v." + SqlIdentifiers.require(idColumn) + " = :id"
            + " ORDER BY v.version_no LIMIT 1";
        return engine().select(sql, Map.of("id", BoundValue.of(id instanceof String text ? UUID.fromString(text) : id)))
            .next()
            .map(row -> String.valueOf(Rows.string(row.get("request_id"))))
            .defaultIfEmpty("")
            .flatMap(requestId -> requestId.startsWith("first-launch-" + run.runId() + "-") ? Mono.<Void>empty()
                : Mono.error(denied(table + " " + id + " was not made by this first launch")));
    }

    /**
     * Refuses unless the user was created by a step of {@code run} and has never had a credential: no password, no
     * second factor, no passkey, no linked identity. Once the person has one, the first launch no longer acts for
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
                + " OR EXISTS (SELECT 1 FROM sec_user_identity_version WHERE user_id = :user)", params)
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
