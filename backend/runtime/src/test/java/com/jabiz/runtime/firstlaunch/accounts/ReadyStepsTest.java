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

    private static FirstLaunchAccountStore.Facts facts(UUID user, String email) {
        return new FirstLaunchAccountStore.Facts(user, email, "name", "en", AT, AT, Set.of(), Set.of(), Set.of(), AT,
            1, true);
    }

    private static final Map<UUID, FirstLaunchAccountStore.Facts> AS_CONFIGURED = Map.of(
        ALICE, facts(ALICE, "a@example.com"), BOB, facts(BOB, "b@example.com"));

    private static LoadWaitingState.State state(String recordedShape, String configShape,
        Map<UUID, FirstLaunchAccountStore.Facts> facts, Map<UUID, Set<String>> done) {
        FirstLaunchRun run = new FirstLaunchRun(RUN, "hash", AT, FirstLaunchRun.State.CONTENT_DONE, AT, null, null);
        return new LoadWaitingState.State(run, List.of(A, B), facts, Map.of(), Set.of(), false, Set.of(), done,
            Set.of(), recordedShape, configShape);
    }

    private static LoadWaitingState.State state(Map<UUID, Set<String>> done) {
        return state(SHAPE, SHAPE, AS_CONFIGURED, done);
    }

    private static LoadWaitingState.State state() {
        return state(Map.of());
    }

    private static WaitingSettings settings(FirstLaunchConfig config) {
        return new WaitingSettings(null, null) {
            @Override
            public Optional<FirstLaunchConfig> config() {
                return Optional.ofNullable(config);
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
        return ready(settings(CONFIG), handler(steps));
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
        ReadySteps.Plan plan = ready(settings(null), handler(List.of(join("join")))).of(state(), A);
        assertThat(plan.steps()).isEmpty();
        assertThat(plan.unavailable()).isTrue();
        assertThat(plan.problem()).isEqualTo(ReadySteps.CONFIG_MISSING);
        // Read, but its directory could not be hashed: the same.
        assertThat(ready(List.of(join("join"))).of(state("", "", AS_CONFIGURED, Map.of()), A).problem())
            .isEqualTo(ReadySteps.CONFIG_MISSING);
    }

    @Test
    void aConfigurationOfAnotherShapeThanTheRunsDrivesNothing() {
        // With the accounts reordered, Bob's place holds Alice: her steps would be done for him.
        ReadySteps steps = ready(List.of(join("join")));
        ReadySteps.Plan plan = steps.of(state(SHAPE, "shape-2", AS_CONFIGURED, Map.of()), A);
        assertThat(plan.steps()).isEmpty();
        assertThat(plan.unavailable()).isTrue();
        assertThat(plan.problem()).isEqualTo(ReadySteps.CONFIG_CHANGED);
        assertThat(steps.of(state("", SHAPE, AS_CONFIGURED, Map.of()), A).unavailable()).as("none recorded").isTrue();
    }

    @Test
    void anAccountsAddressChangedInTheConfigurationDrivesNothingForIt() {
        // The shape leaves addresses out; Alice accepted, so the waiting phase refused the change: not her address.
        ReadySteps steps = ready(List.of(join("join")));
        LoadWaitingState.State changed = state(SHAPE, SHAPE, Map.of(ALICE, facts(ALICE, "alice@other.example"),
            BOB, facts(BOB, "b@example.com")), Map.of());
        ReadySteps.Plan plan = steps.of(changed, A);
        assertThat(plan.steps()).isEmpty();
        assertThat(plan.unavailable()).isTrue();
        assertThat(plan.problem()).isEqualTo(ReadySteps.CONFIG_CHANGED);
        assertThat(steps.of(changed, B).steps()).as("Bob's is his").hasSize(1);
        // Case does not matter.
        assertThat(steps.of(state(SHAPE, SHAPE, Map.of(ALICE, facts(ALICE, "A@Example.com")), Map.of()), A).steps())
            .hasSize(1);
    }

    @Test
    void twoHandlersAreAProblemAsForTheRunner() {
        ReadySteps.Plan plan = ready(settings(CONFIG), handler(List.of(join("join"))),
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
        assertThat(steps.waitingFor(state(Map.of(ALICE, Set.of("join"))), A, ready)).containsExactly("welcome");
        assertThat(steps.waitingFor(state(), A, account(null, null, RoleState.APPLIED, true))).isEmpty();
        assertThat(ready(settings(null), handler(List.of(join("join")))).waitingFor(state(), A, ready))
            .as("the configuration missing: reported, not waited for").isEmpty();
        assertThat(ready(settings(null), handler(List.of(join("join")))).notRun(state(), A))
            .containsExactly(ReadySteps.CONFIG_MISSING);
    }

    /** 17zz29: the report names a step by its description; the key only stands in when there is none. */
    @Test
    void theReportNamesAStepByItsDescription() {
        ReadySteps steps = ready(List.of(join("::").describedAs("Member of operator Tokyo"), join("welcome")));
        assertThat(steps.notRunDescribed(state(), A)).containsExactly("Member of operator Tokyo", "welcome");
        assertThat(steps.notRun(state(), A)).containsExactly("::", "welcome");
        assertThat(steps.notRunDescribed(state(Map.of(ALICE, Set.of("::"))), A)).containsExactly("welcome");
        assertThat(ready(settings(null), handler(List.of(join("join")))).notRunDescribed(state(), A))
            .containsExactly(ReadySteps.CONFIG_MISSING);
    }
}
