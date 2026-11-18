package com.jabiz.app.it.firstlaunch;

import com.jabiz.app.it.fixture.ItApprovalFixtures;
import com.jabiz.context.RequestContext;
import com.jabiz.firstlaunch.FirstLaunchConfig;
import com.jabiz.firstlaunch.FirstLaunchHandler;
import com.jabiz.firstlaunch.FirstLaunchProblem;
import com.jabiz.firstlaunch.FirstLaunchStep;
import com.jabiz.param.ControlledParams;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.approval.ApprovalEntities;
import com.jabiz.runtime.approval.ApprovalProcesses;
import com.jabiz.runtime.approval.ControlChanges;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.firstlaunch.FirstLaunchExit;
import com.jabiz.runtime.firstlaunch.FirstLaunchGate;
import com.jabiz.runtime.firstlaunch.FirstLaunchProcesses;
import com.jabiz.runtime.firstlaunch.FirstLaunchRun;
import com.jabiz.runtime.firstlaunch.FirstLaunchRuns;
import com.jabiz.runtime.param.ParamEntities;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.security.UserProcesses;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The first launch's own powers (decision D82, docs/design/23-first-launch.md section 6): during the content phase a
 * step publishes the initial value of a control the run proposed, a step runs as an account the run created (which
 * has no credential), and a step approves what that account submitted, every level, as {@code first-launch}. Each
 * power refuses anything the run did not make, anything the application did not name, and everything once the
 * content phase is over.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "jabiz.first-launch.await-interval=50ms")
@Import({FirstLaunchPowersIT.Handler.class, ItApprovalFixtures.class})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FirstLaunchPowersIT extends PostgresIntegrationTest {

    static final String RATE = "it.first-launch.rate";
    static final String OTHER = "it.first-launch.other";
    static final String THIRD = "it.first-launch.third";
    static final String LATE = "it.first-launch.late";
    static final String RULE = "FL-PAY";
    static final String LIMITED = "FL-LIMITED";
    static final CountDownLatch RELEASE = new CountDownLatch(1);
    static final List<Integer> EXITS = new CopyOnWriteArrayList<>();
    static volatile boolean holding;

    @DynamicPropertySource
    static void firstLaunch(DynamicPropertyRegistry registry) {
        registry.add("jabiz.first-launch.config", () -> FirstLaunchIT.configFile("version: 1\n"));
    }

    public record Ask() {}

    /** Calls a sub-process the way an event consumer or a job could try to. */
    public record Relay(String process, String changeId, String requestId) {}

    static ControlChanges.ProposeInput param(String key, String value) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("paramKey", key);
        values.put("valueKind", Map.of("type", "numeric", "precision", 5, "scale", 4));
        values.put("value", value);
        values.put("description", "first launch");
        return new ControlChanges.ProposeInput(ParamEntities.ENTITY, null, false, values, null, "first launch");
    }

    static String changeId(Object output) {
        return ((ControlChanges.ChangeOutput) output).changeId();
    }

    @TestConfiguration
    static class Handler {

        @Bean
        ControlledParams firstLaunchItControlled() {
            return ControlledParams.of(RATE, OTHER, THIRD, LATE);
        }

        @Bean
        ProcessDefinition<Ask, Boolean, ProcessContext> firstLaunchItHold() {
            return ProcessDefinition.define("FL_IT_HOLD", 1, Ask.class, Boolean.class, ProcessContext.class, pb -> pb
                .permissions("test.first-launch")
                .contextFactory((start, input) -> new ProcessContext(start))
                .compute("Hold", (metadata, ctx) -> {
                    holding = true;
                    ctx.put("done", RELEASE.getCount() == 0);
                })
                .outputMapper(ctx -> ctx.get("done", Boolean.class)));
        }

        @Bean
        ProcessDefinition<Relay, Relay, ProcessContext> firstLaunchItRelayPublish() {
            return ProcessDefinition.define("FL_IT_RELAY_PUBLISH", 1, Relay.class, Relay.class, ProcessContext.class,
                pb -> pb
                    .permissions("test.first-launch")
                    .contextFactory((start, input) -> {
                        ProcessContext ctx = new ProcessContext(start);
                        ctx.put("in", input);
                        return ctx;
                    })
                    .step("Publish", CallProcess.of(ControlChanges.FIRST_LAUNCH_PUBLISH, 1, ctx ->
                        new ControlChanges.ChangeInput(UUID.fromString(ctx.get("in", Relay.class).changeId())), null))
                    .outputMapper(ctx -> ctx.get("in", Relay.class)));
        }

        @Bean
        ProcessDefinition<Relay, Relay, ProcessContext> firstLaunchItRelayDecide() {
            return ProcessDefinition.define("FL_IT_RELAY_DECIDE", 1, Relay.class, Relay.class, ProcessContext.class,
                pb -> pb
                    .permissions("test.first-launch")
                    .contextFactory((start, input) -> {
                        ProcessContext ctx = new ProcessContext(start);
                        ctx.put("in", input);
                        return ctx;
                    })
                    .step("Decide", CallProcess.of(ApprovalProcesses.FIRST_LAUNCH_DECIDE, 1, ctx ->
                        new ApprovalProcesses.FirstLaunchDecideInput(
                            UUID.fromString(ctx.get("in", Relay.class).requestId())), null))
                    .outputMapper(ctx -> ctx.get("in", Relay.class)));
        }

        @Bean
        FirstLaunchHandler firstLaunchItHandler() {
            return new FirstLaunchHandler() {
                @Override
                public String name() {
                    return "it";
                }

                @Override
                public List<FirstLaunchProblem> validate(FirstLaunchConfig config) {
                    return List.of();
                }

                @Override
                public Set<String> publishableControlKeys() {
                    return Set.of(RATE, RULE, LIMITED, THIRD, LATE);
                }

                @Override
                public Set<String> actingProcesses() {
                    return Set.of(ItApprovalFixtures.PAY.name(), "FL_IT_RELAY_PUBLISH", ControlChanges.PROPOSE);
                }

                @Override
                public List<FirstLaunchStep> plan(FirstLaunchConfig config) {
                    Map<String, Object> rule = new LinkedHashMap<>();
                    rule.put("ruleCode", RULE);
                    rule.put("subject", ItApprovalFixtures.SUBJECT);
                    rule.put("priority", 1);
                    rule.put("enabled", true);
                    rule.put("condition", Map.of("fact", "channel", "op", "eq", "value", "first-launch"));
                    rule.put("levels", List.of(Map.of("permission", "it.approve.one"),
                        Map.of("permission", "it.approve.two")));
                    return List.of(
                        FirstLaunchStep.run("rule", ControlChanges.PROPOSE, results ->
                            new ControlChanges.ProposeInput(ApprovalEntities.RULE, null, false, rule, null, "rule")),
                        FirstLaunchStep.run("rule:publish", ControlChanges.FIRST_LAUNCH_PUBLISH, results ->
                            new ControlChanges.ChangeInput(UUID.fromString(changeId(results.output("rule").get())))),
                        FirstLaunchStep.run("rate", ControlChanges.PROPOSE, results -> param(RATE, "0.2500")),
                        FirstLaunchStep.run("rate:publish", ControlChanges.FIRST_LAUNCH_PUBLISH, results ->
                            new ControlChanges.ChangeInput(UUID.fromString(changeId(results.output("rate").get())))),
                        FirstLaunchStep.run("other", ControlChanges.PROPOSE, results -> param(OTHER, "0.1000")),
                        FirstLaunchStep.run(FirstLaunchStep.accountStepKey("payer"), "SEC_USER_CREATE", results ->
                            new UserProcesses.CreateUserInput("fl-payer", "Payer", null, null, true, null, null,
                                false)),
                        FirstLaunchStep.run(FirstLaunchStep.accountStepKey("sessioned"), "SEC_USER_CREATE", results ->
                            new UserProcesses.CreateUserInput("fl-sessioned", null, null, null, true, null, null,
                                false)),
                        FirstLaunchStep.run(FirstLaunchStep.accountStepKey("signed"), "SEC_USER_CREATE", results ->
                            new UserProcesses.CreateUserInput("fl-signed", null, null, null, true, null, null,
                                false)),
                        FirstLaunchStep.run("pay", ItApprovalFixtures.PAY.name(), results ->
                            new ItApprovalFixtures.PayInput("fl-pay-1", new BigDecimal("10"), "first-launch",
                                "Opening order", null)).asAccount("payer"),
                        FirstLaunchStep.run("pay:approve", ApprovalProcesses.FIRST_LAUNCH_DECIDE, results ->
                            new ApprovalProcesses.FirstLaunchDecideInput(UUID.fromString(results.output("pay",
                                ItApprovalFixtures.PayOutput.class).requestId()))),
                        FirstLaunchStep.await("hold", "FL_IT_HOLD", results -> new Ask()));
                }
            };
        }

        @Bean
        FirstLaunchExit firstLaunchItExit() {
            return EXITS::add;
        }
    }

    @Autowired
    FirstLaunchGate gate;

    @Autowired
    FirstLaunchRuns runs;

    @Autowired
    ProcessExecutor processes;

    private static final RequestContext SYSTEM = RequestContext.system(Locale.ENGLISH, "it");

    private static void within(Duration limit, BooleanSupplier condition) {
        long end = System.nanoTime() + limit.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("Condition not met within " + limit);
            }
            Thread.onSpinWait();
        }
    }

    private FirstLaunchRun run() {
        return runs.current().contextWrite(view -> RequestContexts.put(view, SYSTEM)).block().orElseThrow();
    }

    private <I> void asStep(String requestId, ProcessDefinition<I, ?, ?> process, I input) {
        RequestContext request = RequestContext.system(Locale.ENGLISH, requestId);
        processes.execute(process, input).contextWrite(view -> RequestContexts.put(view, request)).block();
    }

    @SuppressWarnings("unchecked")
    private <I> ProcessDefinition<I, ?, ?> process(String name) {
        return (ProcessDefinition<I, ?, ?>) context().getBean(com.jabiz.runtime.process.ProcessRegistry.class)
            .findLatest(name).orElseThrow();
    }

    @Autowired
    org.springframework.context.ApplicationContext applicationContext;

    private org.springframework.context.ApplicationContext context() {
        return applicationContext;
    }

    private String changeOf(String key) {
        return String.valueOf(query("SELECT change_id FROM sys_control_change_version WHERE target_key = ?"
            + " ORDER BY version_no LIMIT 1", key).getFirst().get("change_id"));
    }

    /** Runs a step of the run, as the platform's runner would: as the system, or acting as {@code actor}. */
    private Object step(String actor, String process, Object input) {
        FirstLaunchRun run = run();
        RequestContext request = actor == null
            ? RequestContext.system(Locale.ENGLISH, "first-launch-" + run.runId() + "-probe")
            : new RequestContext(actor, null, Locale.ENGLISH, "first-launch-" + run.runId() + "-probe", Set.of(),
                Set.of());
        FirstLaunchProcesses.StepOutput output = processes.executeAtEntry(FirstLaunchProcesses.STEP_PROCESS,
                new FirstLaunchProcesses.StepInput(run.runId().toString(), "probe", process, input))
            .contextWrite(view -> RequestContexts.put(view, request)).block();
        return output == null ? null : output.output();
    }

    private String payer() {
        return user("fl-payer");
    }

    private static String user(String name) {
        return String.valueOf(query("SELECT DISTINCT user_id FROM sec_user_version WHERE user_name = ?", name)
            .getFirst().get("user_id"));
    }

    @Test
    @Order(1)
    void duringTheContentPhaseEachPowerRefusesWhatTheRunDidNotMakeOrTheApplicationDidNotName() {
        within(Duration.ofSeconds(60), () -> holding || !EXITS.isEmpty());
        assertThat(EXITS).isEmpty();
        String payer = payer();

        // A change the run proposed, of a control the application did not name.
        assertThatThrownBy(() -> step(null, ControlChanges.FIRST_LAUNCH_PUBLISH,
            new ControlChanges.ChangeInput(UUID.fromString(changeOf(OTHER)))))
            .hasMessageContaining("is not among the controls");
        // Not under a step of the run, whatever the request id says (a relay is the top-level operation).
        assertThatThrownBy(() -> asStep("first-launch-" + run().runId() + "-forged", process("FL_IT_RELAY_PUBLISH"),
            new Relay(null, changeOf(OTHER), null)))
            .hasMessageContaining("only a step of the first launch's content phase");
        // An account under a step, calling the publication through a process it may run: only the system publishes.
        assertThatThrownBy(() -> step(payer, "FL_IT_RELAY_PUBLISH", new Relay(null, changeOf(OTHER), null)))
            .hasMessageContaining("only the system may do this");
        // A request the run did not open: one made outside the run.
        Object outside = processes.execute(ItApprovalFixtures.PAY, new ItApprovalFixtures.PayInput("outside-1",
                new BigDecimal("10"), "first-launch", "Outside", null))
            .contextWrite(view -> RequestContexts.put(view, new RequestContext("it-outsider", null, Locale.ENGLISH,
                "it-outside", Set.of(), Set.of("*")))).block();
        assertThatThrownBy(() -> step(null, ApprovalProcesses.FIRST_LAUNCH_DECIDE,
            new ApprovalProcesses.FirstLaunchDecideInput(UUID.fromString(
                ((ItApprovalFixtures.PayOutput) outside).requestId()))))
            .hasMessageContaining("was not made by this first launch");
        // Acting as a user the run did not create, or running a process the application did not name.
        assertThatThrownBy(() -> step(UUID.randomUUID().toString(), ItApprovalFixtures.PAY.name(),
            new ItApprovalFixtures.PayInput("stranger-1", BigDecimal.ONE, "first-launch", null, null)))
            .hasMessageContaining("was not made by this first launch");
        assertThatThrownBy(() -> step(payer, "SEC_USER_CREATE",
            new UserProcesses.CreateUserInput("fl-sneaky", null, null, null, true, null, null, false)))
            .hasMessageContaining("is not among the processes it may run as an account");

        // A level with an approver limit: the first launch approves no amount.
        Map<String, Object> limited = new LinkedHashMap<>();
        limited.put("ruleCode", LIMITED);
        limited.put("subject", ItApprovalFixtures.SUBJECT);
        limited.put("priority", 2);
        limited.put("enabled", true);
        limited.put("condition", Map.of("fact", "channel", "op", "eq", "value", "limited"));
        limited.put("levels", List.of(Map.of("permission", "it.approve.one", "limitFact", "amount")));
        Object proposal = step(null, ControlChanges.PROPOSE,
            new ControlChanges.ProposeInput(ApprovalEntities.RULE, null, false, limited, null, "limited"));
        step(null, ControlChanges.FIRST_LAUNCH_PUBLISH,
            new ControlChanges.ChangeInput(UUID.fromString(changeId(proposal))));
        Object paid = step(payer, ItApprovalFixtures.PAY.name(),
            new ItApprovalFixtures.PayInput("limited-1", BigDecimal.TEN, "limited", null, null));
        assertThatThrownBy(() -> step(null, ApprovalProcesses.FIRST_LAUNCH_DECIDE,
            new ApprovalProcesses.FirstLaunchDecideInput(UUID.fromString(
                ((ItApprovalFixtures.PayOutput) paid).requestId()))))
            .hasMessageContaining("approves no amount");

        // Only parameters and approval rules: an approver limit is people's to publish, even one the run proposed.
        Object limit = step(null, ControlChanges.PROPOSE, new ControlChanges.ProposeInput(ApprovalEntities.LIMIT, null,
            false, Map.of("userId", payer, "subject", ItApprovalFixtures.SUBJECT, "maxValue", 100), null, "limit"));
        assertThatThrownBy(() -> step(null, ControlChanges.FIRST_LAUNCH_PUBLISH,
            new ControlChanges.ChangeInput(UUID.fromString(changeId(limit)))))
            .hasMessageContaining("publishes no " + ApprovalEntities.LIMIT);
        // Only what the system proposed: a change an account proposed under a step is not published.
        Object byAccount = step(payer, ControlChanges.PROPOSE, param(THIRD, "0.3000"));
        assertThatThrownBy(() -> step(null, ControlChanges.FIRST_LAUNCH_PUBLISH,
            new ControlChanges.ChangeInput(UUID.fromString(changeId(byAccount)))))
            .hasMessageContaining("only what the system proposed");
        // Only what the run proposed: the system's proposal outside the run (a job, say) is not published.
        Object outsideProposal = processes.execute(ControlChanges.propose(context().getBean(
                com.jabiz.runtime.approval.ControlTargets.class)), param(LATE, "0.4000"))
            .contextWrite(view -> RequestContexts.put(view, RequestContext.system(Locale.ENGLISH, "it-job"))).block();
        assertThatThrownBy(() -> step(null, ControlChanges.FIRST_LAUNCH_PUBLISH,
            new ControlChanges.ChangeInput(UUID.fromString(changeId(outsideProposal)))))
            .hasMessageContaining("was not made by this first launch");

        // A session, or a successful sign-in (an e-mail link signs in without a password), is a credential too.
        long seq = (Long) query("SELECT max(process_seq_id) AS seq FROM op_process").getFirst().get("seq");
        String sessioned = user("fl-sessioned");
        execute("INSERT INTO sec_refresh_token (token_hash, family_id, user_id, issued_at, expires_at)"
            + " VALUES (?, ?, ?::uuid, now(), now() + interval '1 day')", "a".repeat(64), UUID.randomUUID(), sessioned);
        assertThatThrownBy(() -> step(sessioned, ItApprovalFixtures.PAY.name(),
            new ItApprovalFixtures.PayInput("sessioned-1", BigDecimal.ONE, "first-launch", null, null)))
            .hasMessageContaining("has a credential of its own");
        String signed = user("fl-signed");
        UUID record = UUID.randomUUID();
        execute("INSERT INTO entity_registry (entity_id, entity_type, created_seq_id) VALUES (?, 'SecLoginRecord', ?)",
            record, seq);
        execute("INSERT INTO sec_login_record_version (login_record_id, version_no, effect_start_time, created_time,"
            + " process_seq_id, user_id, user_name, attempt_no, outcome, failure_count, attempt_time)"
            + " VALUES (?, 1, now(), now(), ?, ?::uuid, 'fl-signed', 1, 'SUCCESS', 0, now())", record, seq, signed);
        assertThatThrownBy(() -> step(signed, ItApprovalFixtures.PAY.name(),
            new ItApprovalFixtures.PayInput("signed-1", BigDecimal.ONE, "first-launch", null, null)))
            .hasMessageContaining("has a credential of its own");

        // Once the person has a credential of their own, the first launch no longer acts as them.
        processes.execute(UserProcesses.SET_PASSWORD, new UserProcesses.SetPasswordInput(payer,
                "a password of their own 1"))
            .contextWrite(view -> RequestContexts.put(view, new RequestContext("it-admin", null, Locale.ENGLISH,
                "it-admin", Set.of(), Set.of("*")))).block();
        assertThatThrownBy(() -> step(payer, ItApprovalFixtures.PAY.name(),
            new ItApprovalFixtures.PayInput("own-1", BigDecimal.ONE, "first-launch", null, null)))
            .hasMessageContaining("has a credential of its own");
    }

    @Test
    @Order(2)
    void theRunPublishedApprovedAndActedAsTheFirstLaunch() {
        RELEASE.countDown();
        within(Duration.ofSeconds(30), gate::isOpen);
        FirstLaunchRun run = run();
        assertThat(run.state()).isEqualTo(FirstLaunchRun.State.CONTENT_DONE);

        // The initial values took effect, published by the first launch; the control it may not publish did not.
        assertThat(query("SELECT target_key, status, published_by FROM sys_control_change_version v"
            + " WHERE version_no = (SELECT max(version_no) FROM sys_control_change_version w"
            + " WHERE w.change_id = v.change_id) AND target_key IN (?, ?, ?, ?) ORDER BY target_key",
            LIMITED, RULE, OTHER, RATE))
            .extracting(row -> row.get("target_key") + "=" + row.get("status") + "/" + row.get("published_by"))
            .containsExactly(LIMITED + "=PUBLISHED/first-launch", RULE + "=PUBLISHED/first-launch",
                OTHER + "=PROPOSED/null", RATE + "=PUBLISHED/first-launch");
        assertThat(query("SELECT param_value AS value FROM sys_param_version WHERE param_key = ? ORDER BY version_no DESC LIMIT 1",
            RATE).getFirst().get("value").toString()).isEqualTo("0.2500");

        // The payment was submitted as the account and approved at both levels by the first launch.
        assertThat(query("SELECT actor_id FROM op_process WHERE process_name = 'IT_PAY' AND request_id LIKE ?",
            "first-launch-%").getFirst().get("actor_id")).isNotEqualTo(RequestContext.SYSTEM_ACTOR);
        assertThat(query("SELECT level_no, approver_id, reason FROM sys_approval_decision ORDER BY level_no"))
            .extracting(row -> row.get("level_no") + ":" + row.get("approver_id"))
            .containsExactly("1:first-launch:1", "2:first-launch:2");
        assertThat(query("SELECT reason FROM sys_approval_decision").getFirst().get("reason").toString())
            .contains(run.configHash());
        assertThat(query("SELECT count(*) AS events FROM sys_outbox_event WHERE event_type = ?",
            ApprovalProcesses.APPROVED_EVENT).getFirst().get("events")).isEqualTo(1L);
    }

    @Test
    @Order(3)
    void afterTheContentPhaseNoPowerWorks() {
        assertThatThrownBy(() -> step(null, ControlChanges.FIRST_LAUNCH_PUBLISH,
            new ControlChanges.ChangeInput(UUID.fromString(changeOf(OTHER)))))
            .hasMessageContaining("content phase is over");
        assertThatThrownBy(() -> step(payer(), ItApprovalFixtures.PAY.name(),
            new ItApprovalFixtures.PayInput("late-1", BigDecimal.ONE, "first-launch", null, null)))
            .hasMessageContaining("content phase is over");
    }
}
