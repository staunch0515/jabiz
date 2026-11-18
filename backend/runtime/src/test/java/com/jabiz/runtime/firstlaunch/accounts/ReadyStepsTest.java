package com.jabiz.runtime.firstlaunch.accounts;

import com.jabiz.firstlaunch.FirstLaunchAccount;
import com.jabiz.firstlaunch.FirstLaunchConfig;
import com.jabiz.firstlaunch.FirstLaunchHandler;
import com.jabiz.firstlaunch.FirstLaunchProblem;
import com.jabiz.firstlaunch.FirstLaunchResults;
import com.jabiz.firstlaunch.FirstLaunchStep;
import com.jabiz.runtime.firstlaunch.FirstLaunchRun;
import com.jabiz.runtime.firstlaunch.accounts.GrantPlan.Account;
import com.jabiz.runtime.firstlaunch.accounts.GrantPlan.Role;
import com.jabiz.runtime.firstlaunch.accounts.GrantPlan.RoleState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The application's steps for a ready account, as checked before any runs (docs/design/23-first-launch.md section 9). */
class ReadyStepsTest {

    private static final UUID RUN = UUID.fromString("0190a1b2-0000-7000-8000-0000000000f1");
    private static final UUID ALICE = UUID.fromString("0190a1b2-0000-7000-8000-00000000000a");
    private static final UUID BOB = UUID.fromString("0190a1b2-0000-7000-8000-00000000000b");
    private static final Instant AT = Instant.parse("2026-12-01T00:00:00Z");
    private static final String SHAPE = "shape-1";

    private static final FirstLaunchConfig CONFIG = new FirstLaunchConfig("hash", Map.of(), "en", List.of(),
        List.of(new FirstLaunchAccount("alice", "a@example.com", "Alice", "en", List.of("STAFF"), false),
            new FirstLaunchAccount("bob", "b@example.com", "Bob", "en", List.of("STAFF"), false)),
        new FirstLaunchConfig.Files() {
            @Override
            public byte[] read(String relativePath) {
                throw new IllegalArgumentException(relativePath);
            }

            @Override
            public boolean exists(String relativePath) {
                return false;
            }
        });

    private static final FirstLaunchAccountStore.Recorded A = new FirstLaunchAccountStore.Recorded(RUN, ALICE, 0,
        List.of("STAFF"), false);
    private static final FirstLaunchAccountStore.Recorded B = new FirstLaunchAccountStore.Recorded(RUN, BOB, 1,
        List.of("STAFF"), false);

    private static LoadWaitingState.State state(String recordedShape, Map<UUID, Set<String>> done) {
        FirstLaunchRun run = new FirstLaunchRun(RUN, "hash", AT, FirstLaunchRun.State.CONTENT_DONE, AT, null, null);
        return new LoadWaitingState.State(run, List.of(A, B), Map.of(), Map.of(), Set.of(), false, Set.of(), done,
            Set.of(), recordedShape);
    }

    private static LoadWaitingState.State state() {
        return state(SHAPE, Map.of());
    }

    private static WaitingSettings settings(FirstLaunchConfig config, String shape) {
        return new WaitingSettings(null, null) {
            @Override
            public Optional<FirstLaunchConfig> config() {
                return Optional.ofNullable(config);
            }

            @Override
            public Optional<String> shape() {
                return Optional.ofNullable(shape);
            }
        };
    }

    private static FirstLaunchHandler handler(List<FirstLaunchStep> steps) {
        return new FirstLaunchHandler() {
            @Override
            public String name() {
                return "test";
            }

            @Override
            public List<FirstLaunchProblem> validate(FirstLaunchConfig config) {
                return List.of();
            }

            @Override
            public List<FirstLaunchStep> plan(FirstLaunchConfig config) {
                return List.of();
            }

            @Override
            public Set<String> accountReadyProcesses() {
                return Set.of("JOIN");
            }

            @Override
            public List<FirstLaunchStep> whenAccountReady(FirstLaunchConfig config, FirstLaunchAccount account,
                FirstLaunchResults results) {
                return steps;
            }
        };
    }

    private static ReadySteps ready(WaitingSettings settings, FirstLaunchHandler... handlers) {
        Map<String, Object> beans = new LinkedHashMap<>();
        for (int i = 0; i < handlers.length; i++) {
            beans.put("handler" + i, handlers[i]);
        }
        return new ReadySteps(settings, new StaticListableBeanFactory(beans).getBeanProvider(FirstLaunchHandler.class));
    }

    private static ReadySteps ready(List<FirstLaunchStep> steps) {
        return ready(settings(CONFIG, SHAPE), handler(steps));
    }

    private static FirstLaunchStep join(String key) {
        return FirstLaunchStep.run(key, "JOIN", results -> results.userId("bob"));
    }

    private static Account account(Instant accepted, Instant mfa, RoleState state, boolean enabled) {
        return new Account("alice", ALICE, List.of(new Role("STAFF", false)), accepted, mfa,
            state == null ? Map.of() : Map.of("STAFF", state), false, AT, false, enabled, true);
    }

    @Test
    void runStepsOfTheListedProcessesWithUniqueKeysAreGivenWithTheAccountsUserIds() {
        ReadySteps.Plan plan = ready(List.of(join("join"), join("welcome"))).of(state(), A);
        assertThat(plan.problem()).isNull();
        assertThat(plan.unavailable()).isFalse();
        assertThat(plan.steps()).extracting(FirstLaunchStep::key).containsExactly("join", "welcome");
        assertThat(plan.steps().getFirst().input().apply(plan.results())).isEqualTo(BOB.toString());
    }

    @Test
    void aProcessNotAmongTheAccountReadyProcessesIsAProblemAndNothingRuns() {
        ReadySteps.Plan plan = ready(List.of(join("join"), FirstLaunchStep.run("x", "OTHER", results -> null)))
            .of(state(), A);
        assertThat(plan.steps()).isEmpty();
        assertThat(plan.unavailable()).isFalse();
        assertThat(plan.problem()).contains("OTHER").contains("accountReadyProcesses");
    }

    @Test
    void actingAsAnAccountIsAProblem() {
        ReadySteps.Plan plan = ready(List.of(join("join").asAccount("bob"))).of(state(), A);
        assertThat(plan.steps()).isEmpty();
        assertThat(plan.problem()).contains("acts as an account");
    }

    @Test
    void waitingIsAProblem() {
        ReadySteps.Plan plan = ready(List.of(FirstLaunchStep.await("join", "JOIN", results -> null)))
            .of(state(), A);
        assertThat(plan.steps()).isEmpty();
        assertThat(plan.problem()).contains("waits");
    }

    @Test
    void aRepeatedKeyIsAProblem() {
        ReadySteps.Plan plan = ready(List.of(join("join"), join("join"))).of(state(), A);
        assertThat(plan.steps()).isEmpty();
        assertThat(plan.problem()).contains("repeats a step key");
    }

    @Test
    void aKeyTooLongOrHoldingTheAccountsKeyIsAProblem() {
        assertThat(ready(List.of(join("k".repeat(ReadySteps.MAX_KEY)))).of(state(), A).problem()).isNull();
        assertThat(ready(List.of(join("k".repeat(ReadySteps.MAX_KEY + 1)))).of(state(), A).problem())
            .contains("longer than 200");
        ReadySteps.Plan named = ready(List.of(join("join-alice"))).of(state(), A);
        assertThat(named.steps()).isEmpty();
        assertThat(named.problem()).contains("account's key").doesNotContain("join-alice");
    }

    @Test
    void withoutTheConfigurationNothingRunsAndNothingFails() {
        ReadySteps.Plan plan = ready(settings(null, null), handler(List.of(join("join")))).of(state(), A);
        assertThat(plan.steps()).isEmpty();
        assertThat(plan.unavailable()).isTrue();
        assertThat(plan.problem()).isEqualTo(ReadySteps.CONFIG_MISSING);
    }

    @Test
    void aConfigurationOfAnotherShapeThanTheRunsDrivesNothing() {
        // With the accounts reordered, Bob's place holds Alice: her steps would be done for him.
        ReadySteps steps = ready(settings(CONFIG, "shape-2"), handler(List.of(join("join"))));
        ReadySteps.Plan plan = steps.of(state(), A);
        assertThat(plan.steps()).isEmpty();
        assertThat(plan.unavailable()).isTrue();
        assertThat(plan.problem()).isEqualTo(ReadySteps.CONFIG_CHANGED);
        assertThat(steps.of(state("", Map.of()), A).unavailable()).as("none recorded").isTrue();
    }

    @Test
    void twoHandlersAreAProblemAsForTheRunner() {
        ReadySteps.Plan plan = ready(settings(CONFIG, SHAPE), handler(List.of(join("join"))),
            handler(List.of(join("join")))).of(state(), A);
        assertThat(plan.steps()).isEmpty();
        assertThat(plan.problem()).contains("2 first-launch handlers");
    }

    @Test
    void anAccountIsReadyOnlyEnabledAcceptedWithASecondFactorAndAConfiguredRoleInEffect() {
        assertThat(ReadySteps.ready(account(AT, AT, RoleState.APPLIED, true))).isTrue();
        // A role an administrator gave by hand before the invitation was accepted is not readiness.
        assertThat(ReadySteps.ready(account(null, null, RoleState.APPLIED, true))).isFalse();
        assertThat(ReadySteps.ready(account(AT, null, RoleState.APPLIED, true))).isFalse();
        assertThat(ReadySteps.ready(account(AT, AT, RoleState.APPLIED, false))).isFalse();
        assertThat(ReadySteps.ready(account(AT, AT, RoleState.PENDING, true))).isFalse();
        assertThat(ReadySteps.ready(account(AT, AT, null, true))).isFalse();
    }

    @Test
    void onlyAReadyAccountsStepsNotRunAreWaitedFor() {
        ReadySteps steps = ready(List.of(join("join"), join("welcome")));
        Account ready = account(AT, AT, RoleState.APPLIED, true);
        assertThat(steps.waitingFor(state(), A, ready)).containsExactly("join", "welcome");
        assertThat(steps.waitingFor(state(SHAPE, Map.of(ALICE, Set.of("join"))), A, ready)).containsExactly("welcome");
        assertThat(steps.waitingFor(state(), A, account(null, null, RoleState.APPLIED, true))).isEmpty();
        assertThat(ready(settings(null, null), handler(List.of(join("join")))).waitingFor(state(), A, ready))
            .as("the configuration missing: reported, not waited for").isEmpty();
        assertThat(ready(settings(null, null), handler(List.of(join("join")))).notRun(state(), A))
            .containsExactly(ReadySteps.CONFIG_MISSING);
    }
}
