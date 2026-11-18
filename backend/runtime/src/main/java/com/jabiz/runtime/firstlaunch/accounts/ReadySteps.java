package com.jabiz.runtime.firstlaunch.accounts;

import com.jabiz.firstlaunch.FirstLaunchAccount;
import com.jabiz.firstlaunch.FirstLaunchConfig;
import com.jabiz.firstlaunch.FirstLaunchHandler;
import com.jabiz.firstlaunch.FirstLaunchResults;
import com.jabiz.firstlaunch.FirstLaunchStep;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The application's steps for an account once it is ready in the waiting phase
 * ({@link FirstLaunchHandler#whenAccountReady}; docs/design/23-first-launch.md section 9): asked of the handler with
 * the configuration this instance started with, and checked - {@code run} steps only, processes of
 * {@link FirstLaunchHandler#accountReadyProcesses()} only, keys unique, at most {@value #MAX_KEY} characters and not
 * holding the account's key (its user name: keys are recorded). Only while the configuration is the run's (its shape,
 * {@link ConfigShape}, as recorded with {@code CONTENT_DONE}). Pure but for the configuration read at startup.
 */
@Component
public class ReadySteps {

    /**
     * What the handler gives for an account: the steps, or why none can be had.
     *
     * @param unavailable the configuration is not there, or is not the one the run recorded (another shape): the steps
     *                    cannot be had - they are not run, not waited for, and reported
     */
    public record Plan(List<FirstLaunchStep> steps, FirstLaunchResults results, String problem,
        boolean unavailable) {
        public Plan {
            steps = List.copyOf(steps);
        }

        static Plan none() {
            return new Plan(List.of(), null, null, false);
        }

        static Plan problem(String problem) {
            return new Plan(List.of(), null, problem, false);
        }
    }

    /** The problem when the configuration is not there. */
    static final String CONFIG_MISSING = "the first-launch configuration is not there (or does not read): the"
        + " application's steps for a ready account cannot run; put it back and restart";
    /** The problem when the configuration is not the run's. */
    static final String CONFIG_CHANGED = "the first-launch configuration differs from the run's (in more than an"
        + " address, or in this account's address, which it has accepted): the application's steps for the account do"
        + " not run on it; put the run's configuration back and restart";
    /** The longest step key ({@code sys_first_launch_ready.step_key}). */
    static final int MAX_KEY = 200;

    private final WaitingSettings settings;
    private final ObjectProvider<FirstLaunchHandler> handlers;

    public ReadySteps(WaitingSettings settings, ObjectProvider<FirstLaunchHandler> handlers) {
        this.settings = settings;
        this.handlers = handlers;
    }

    /** The steps for the run's account {@code recorded}. */
    public Plan of(LoadWaitingState.State state, FirstLaunchAccountStore.Recorded recorded) {
        // As the runner and the startup check take the handler: one, or a problem.
        List<FirstLaunchHandler> found = handlers.orderedStream().toList();
        if (found.isEmpty()) {
            return Plan.none();
        }
        if (found.size() > 1) {
            return Plan.problem("the application has " + found.size() + " first-launch handlers");
        }
        Optional<FirstLaunchHandler> handler = Optional.of(found.getFirst());
        if (handler.get().accountReadyProcesses().isEmpty()) {
            return Plan.none();
        }
        Optional<FirstLaunchConfig> config = settings.config();
        if (config.isEmpty()) {
            return new Plan(List.of(), null, CONFIG_MISSING, true);
        }
        // A configuration the waiting phase refused (decision D83: more than the addresses differ) drives nothing:
        // with the accounts moved about, one account's steps would be done for another.
        if (state.configShape().isEmpty()) {
            return new Plan(List.of(), null, CONFIG_MISSING, true);
        }
        if (!state.configShape().equals(state.recordedShape())) {
            return new Plan(List.of(), null, CONFIG_CHANGED, true);
        }
        List<FirstLaunchAccount> accounts = config.get().accounts();
        if (recorded.position() >= accounts.size()) {
            return Plan.problem("the configuration has no account at place " + recorded.label());
        }
        // The shape leaves the addresses out. An address the waiting phase refused (the account had accepted, D83)
        // is not one its user proved: the handler is not given it.
        FirstLaunchAccountStore.Facts facts = state.facts().get(recorded.userId());
        String configured = accounts.get(recorded.position()).email();
        if (facts != null && (configured == null || facts.email() == null
            || !configured.equalsIgnoreCase(facts.email()))) {
            return new Plan(List.of(), null, CONFIG_CHANGED, true);
        }
        FirstLaunchResults results = results(state, accounts);
        List<FirstLaunchStep> steps = handler.get().whenAccountReady(config.get(), accounts.get(recorded.position()),
            results);
        Set<String> allowed = handler.get().accountReadyProcesses();
        Set<String> keys = new HashSet<>();
        List<String> problems = new ArrayList<>();
        String accountKey = accounts.get(recorded.position()).key();
        for (FirstLaunchStep step : steps) {
            if (!keys.add(step.key())) {
                problems.add("repeats a step key");
            }
            if (step.key().length() > MAX_KEY) {
                problems.add("a step key is longer than " + MAX_KEY + " characters");
            }
            // Step keys are recorded (sys_first_launch_ready); the account's key is its user's name.
            if (step.key().contains(accountKey)) {
                problems.add("a step key holds the account's key (its user name), which is not recorded");
            }
            if (step.asAccount() != null || step.await()) {
                problems.add("a step acts as an account or waits; only run steps are allowed here");
            }
            if (!allowed.contains(step.process())) {
                problems.add("a step runs " + step.process() + ", which is not among the handler's"
                    + " accountReadyProcesses()");
            }
        }
        return problems.isEmpty() ? new Plan(steps, results, null, false) : Plan.problem(String.join("; ", problems));
    }

    /**
     * The keys of the application's steps for the run's account that have not run and are still to be waited for: the
     * account is ready, the step has not run, its item has not failed, and the configuration is there (a failure or a
     * missing configuration is left to people, and listed).
     */
    public List<String> waitingFor(LoadWaitingState.State state, FirstLaunchAccountStore.Recorded recorded,
        GrantPlan.Account account) {
        if (account == null || !ready(account) || state.readyFailed().contains(recorded.userId())
            || of(state, recorded).unavailable()) {
            return List.of();
        }
        return notRun(state, recorded);
    }

    /** The keys of the application's steps for the account that have not run (a problem: one key, the problem). */
    public List<String> notRun(LoadWaitingState.State state, FirstLaunchAccountStore.Recorded recorded) {
        Plan plan = of(state, recorded);
        if (plan.problem() != null) {
            return List.of(plan.problem());
        }
        Set<String> done = state.readyDone().getOrDefault(recorded.userId(), Set.of());
        return plan.steps().stream().map(FirstLaunchStep::key).filter(key -> !done.contains(key)).toList();
    }

    /**
     * Whether the account is ready for the application's steps: enabled, its invitation accepted, a second factor
     * bound - not merely holding a role, which an administrator may have given by hand - and a configured role in
     * effect; not left to people.
     */
    static boolean ready(GrantPlan.Account account) {
        return !account.leftToPeople() && account.ready() && account.anyApplied();
    }

    /** The account's user ids by key, through the accounts' places; no outputs. */
    private static FirstLaunchResults results(LoadWaitingState.State state, List<FirstLaunchAccount> accounts) {
        return new FirstLaunchResults() {
            @Override
            public Optional<Object> output(String stepKey) {
                return Optional.empty();
            }

            @Override
            public String userId(String accountKey) {
                for (int i = 0; i < accounts.size(); i++) {
                    if (accounts.get(i).key().equals(accountKey)) {
                        int position = i;
                        return state.accounts().stream().filter(account -> account.position() == position)
                            .map(account -> account.userId().toString()).findFirst()
                            .orElseThrow(() -> new IllegalStateException("No account of the run at place " + position));
                    }
                }
                throw new IllegalArgumentException("No such account in the configuration");
            }
        };
    }

    static UUID fromHex(String hex) {
        return UUID.fromString(hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16) + "-"
            + hex.substring(16, 20) + "-" + hex.substring(20));
    }
}
