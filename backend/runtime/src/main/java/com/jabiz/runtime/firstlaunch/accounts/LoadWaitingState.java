package com.jabiz.runtime.firstlaunch.accounts;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSpec;
import com.jabiz.runtime.firstlaunch.FirstLaunchRun;
import com.jabiz.runtime.firstlaunch.FirstLaunchRuns;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.runtime.security.protect.ProtectedGrants;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Reads what the waiting phase judges by into the context ({@link #STATE}): the run, its accounts, their users now,
 * the configured roles and which of them make an administrator (decision D51), and whether the setup window of
 * protected grants has ended. {@code null} when the run is not waiting (no run, or not {@code CONTENT_DONE}). A
 * platform I/O step.
 */
@Component
public class LoadWaitingState<C extends ProcessContext> implements StepHandler<LoadWaitingState.Metadata, C> {

    public static final String STATE = "first_launch_waiting";

    /** @param ended whether a run that has ended is read too (its report) */
    public record Metadata(boolean ended) {}

    /**
     * @param roles      the configured roles and the waiting role, by code (a code without a role is missing)
     * @param adminRoles the codes among them that make an administrator
     * @param armed      whether the setup window of protected grants has ended
     * @param invitable  the users an invitation may go to: their address is the one entered at creation or corrected
     *                   by the first launch (decision D63, D83)
     */
    public record State(FirstLaunchRun run, List<FirstLaunchAccountStore.Recorded> accounts,
        Map<UUID, FirstLaunchAccountStore.Facts> facts, Map<String, FirstLaunchAccountStore.Role> roles,
        Set<String> adminRoles, boolean armed, Set<UUID> invitable) {}

    public static <C extends ProcessContext> StepSpec<Metadata, C> of() {
        return StepSpec.of(LoadWaitingState.class, new Metadata(false));
    }

    /** As {@link #of()}, for a run that has ended as well: its report. */
    public static <C extends ProcessContext> StepSpec<Metadata, C> ofEnded() {
        return StepSpec.of(LoadWaitingState.class, new Metadata(true));
    }

    private final FirstLaunchRuns runs;
    private final FirstLaunchAccountStore store;
    private final ProtectedGrants grants;

    public LoadWaitingState(FirstLaunchRuns runs, FirstLaunchAccountStore store, ProtectedGrants grants) {
        this.runs = runs;
        this.store = store;
        this.grants = grants;
    }

    @Override
    public Mono<Void> execute(Metadata metadata, C ctx) {
        return runs.current().flatMap(found -> {
            FirstLaunchRun.State state = found.map(FirstLaunchRun::state).orElse(null);
            boolean wanted = state == FirstLaunchRun.State.CONTENT_DONE || metadata.ended()
                && (state == FirstLaunchRun.State.DONE || state == FirstLaunchRun.State.EXPIRED);
            if (!wanted) {
                return Mono.empty();
            }
            FirstLaunchRun run = found.get();
            return store.accounts(run.runId()).flatMap(accounts -> {
                Set<String> codes = new LinkedHashSet<>();
                codes.add(WaitingRole.CODE);
                accounts.forEach(account -> codes.addAll(account.roles()));
                List<UUID> users = accounts.stream().map(FirstLaunchAccountStore.Recorded::userId).toList();
                Mono<Set<UUID>> invitable = Flux.fromIterable(users)
                    .concatMap(user -> grants.credentials(user)
                        .filter(ProtectedGrants.Credentials::addressAsCreated).map(credentials -> user))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
                return Mono.zip(store.facts(users, ctx.opTime()), store.roles(codes, ctx.opTime()),
                        grants.armedOnRecord(), invitable)
                    .flatMap(read -> store.rolesHolding(read.getT2().values().stream()
                                .map(FirstLaunchAccountStore.Role::roleId).toList(),
                            grants.settings().administratorPermissions(), ctx.opTime())
                        .doOnNext(admins -> ctx.put(STATE, new State(run, accounts, read.getT1(), read.getT2(),
                            admins, read.getT3(), read.getT4())))
                        .then());
            });
        });
    }
}
