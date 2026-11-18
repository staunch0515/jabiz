package com.jabiz.app.it.security;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;
import com.jabiz.context.RequestContext;
import com.jabiz.firstlaunch.FirstLaunchAccount;
import com.jabiz.firstlaunch.FirstLaunchConfig;
import com.jabiz.firstlaunch.FirstLaunchHandler;
import com.jabiz.firstlaunch.FirstLaunchProblem;
import com.jabiz.firstlaunch.FirstLaunchResults;
import com.jabiz.firstlaunch.FirstLaunchStep;
import com.jabiz.job.JobDefinition;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.firstlaunch.FirstLaunchExit;
import com.jabiz.runtime.firstlaunch.FirstLaunchRun;
import com.jabiz.runtime.firstlaunch.FirstLaunchRuns;
import com.jabiz.runtime.firstlaunch.accounts.ConfigShape;
import com.jabiz.runtime.firstlaunch.accounts.FirstLaunchRevisions;
import com.jabiz.runtime.firstlaunch.accounts.FirstLaunchWaiting;
import com.jabiz.runtime.firstlaunch.accounts.WaitingRole;
import com.jabiz.runtime.firstlaunch.accounts.WaitingSettings;
import com.jabiz.runtime.job.JobRunner;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.security.SecurityEntities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * When the application's steps for a ready account do not run (docs/design/23-first-launch.md section 9): the account
 * is not ready though it holds a role (an administrator gave it by hand), the configuration is missing or not the run's
 * (it drives nothing; not waited for, reported), the user is not the run's, or the run has ended; and a step runs once
 * however often its item is replayed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "jabiz.first-launch.await-interval=50ms", "jabiz.jobs.lock-at-least-for=0s",
    "jabiz.mail.enabled=true", "jabiz.mail.from=jabiz@example.com", "jabiz.mail.base-url=https://app.example.com",
    "spring.mail.host=localhost", "spring.mail.port=3048",
    "jabiz.security.entries.admin.accepted-roles=IT_STAFF," + WaitingRole.CODE})
@Import({SignUpItSupport.FixedTimeLimits.class, FirstLaunchReadyGuardsIT.Handler.class})
@ExtendWith(OutputCaptureExtension.class)
class FirstLaunchReadyGuardsIT extends SignUpItSupport {

    @RegisterExtension
    static final GreenMailExtension MAIL = new GreenMailExtension(new ServerSetup(3048, null, ServerSetup.PROTOCOL_SMTP));

    static final String A = "fl-g-a@example.com";
    static final String B = "fl-g-b@example.com";
    static final String OWN = "own horse battery";
    /** The actor and the member of each run of the application's step. */
    static final List<String> JOINED = new CopyOnWriteArrayList<>();

    /** How the configuration this instance "started with" is seen. */
    enum Mode { AS_STARTED, CHANGED, MISSING }

    static volatile Mode mode = Mode.AS_STARTED;

    @DynamicPropertySource
    static void firstLaunch(DynamicPropertyRegistry registry) {
        String path = FirstLaunchWaitingIT.configFileIn("""
            version: 1
            site:
              language: en
            allowedEmailDomains: [example.com]
            accounts:
              - key: fl-g-alice
                email: %s
                name: Alice
                roles: [IT_STAFF]
                report: true
              - key: fl-g-bob
                email: %s
                name: Bob
                roles: [IT_STAFF]
            """.formatted(A, B));
        registry.add("jabiz.first-launch.config", () -> path);
    }

    public record Roles() {}

    public record Join(String member) {}

    @TestConfiguration
    static class Handler {

        /** The configuration as started, or with its accounts in the other order (another shape), or none. */
        @Bean
        @Primary
        WaitingSettings firstLaunchReadyGuardsSettings(Environment environment, ConfigShape shapes) {
            return new WaitingSettings(environment, shapes) {
                @Override
                public Optional<FirstLaunchConfig> config() {
                    return switch (mode) {
                        case AS_STARTED -> super.config();
                        case MISSING -> Optional.empty();
                        case CHANGED -> super.config().map(Handler::reordered);
                    };
                }

                @Override
                public Optional<String> shape() {
                    return mode == Mode.AS_STARTED ? super.shape() : config().map(shapes::of);
                }
            };
        }

        static FirstLaunchConfig reordered(FirstLaunchConfig config) {
            Map<String, Object> document = new LinkedHashMap<>(config.document());
            List<Object> accounts = new ArrayList<>((List<?>) document.get("accounts"));
            java.util.Collections.reverse(accounts);
            document.put("accounts", accounts);
            List<FirstLaunchAccount> reordered = new ArrayList<>(config.accounts());
            java.util.Collections.reverse(reordered);
            return new FirstLaunchConfig(config.hash() + "-reordered", document, config.language(),
                config.allowedEmailDomains(), reordered, config.files());
        }

        @Bean
        ProcessDefinition<Roles, Boolean, ProcessContext> firstLaunchReadyGuardsRoles() {
            return ProcessDefinition.define("FL_GUARD_IT_ROLES", 1, Roles.class, Boolean.class, ProcessContext.class,
                pb -> pb
                    .permissions("test.first-launch")
                    .contextFactory((start, input) -> new ProcessContext(start))
                    .compute("Roles", (metadata, ctx) -> {
                        Object staff = ctx.changes().insert(SecurityEntities.ROLE, Map.of("roleCode", "IT_STAFF",
                            "labels", Map.of("en", "Staff"), "enabled", true));
                        ctx.changes().insert(SecurityEntities.ROLE_PERMISSION, Map.of("roleId", staff,
                            "permission", "price.read"));
                    })
                    .outputMapper(ctx -> true));
        }

        @Bean
        ProcessDefinition<Join, Boolean, ProcessContext> firstLaunchReadyGuardsJoin() {
            return ProcessDefinition.define("FL_GUARD_IT_JOIN", 1, Join.class, Boolean.class, ProcessContext.class,
                pb -> pb
                    .permissions("test.first-launch")
                    .subProcessOnly()
                    .contextFactory((start, input) -> {
                        ProcessContext ctx = new ProcessContext(start);
                        ctx.put("in", input);
                        return ctx;
                    })
                    .compute("Join", (metadata, ctx) -> JOINED.add(ctx.request().actorId() + " "
                        + ctx.get("in", Join.class).member()))
                    .outputMapper(ctx -> true));
        }

        @Bean
        FirstLaunchHandler firstLaunchReadyGuardsHandler() {
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
                public List<FirstLaunchStep> plan(FirstLaunchConfig config) {
                    return List.of(FirstLaunchStep.run("roles", "FL_GUARD_IT_ROLES", results -> new Roles()));
                }

                @Override
                public Set<String> accountReadyProcesses() {
                    return Set.of("FL_GUARD_IT_JOIN");
                }

                @Override
                public List<FirstLaunchStep> whenAccountReady(FirstLaunchConfig config, FirstLaunchAccount account,
                    FirstLaunchResults results) {
                    return List.of(FirstLaunchStep.run("join", "FL_GUARD_IT_JOIN",
                        found -> new Join(found.userId(account.key()))));
                }
            };
        }

        @Bean
        FirstLaunchExit firstLaunchReadyGuardsExit() {
            return code -> {
                throw new AssertionError("The first launch exited with " + code);
            };
        }
    }

    @Autowired
    FirstLaunchRuns runs;

    @Autowired
    JobRunner jobs;

    @Autowired
    ProcessExecutor processes;

    @Autowired
    FirstLaunchRevisions revisions;

    @Autowired
    @Qualifier("firstLaunchAccountReadyProcess")
    ProcessDefinition<FirstLaunchWaiting.ReadyInput, FirstLaunchWaiting.ReadyOutput, ProcessContext> ready;

    @Autowired
    @Qualifier("firstLaunchJob")
    JobDefinition<FirstLaunchWaiting.AdvanceInput> job;

    private static int round;

    @Override
    GreenMailExtension mail() {
        return MAIL;
    }

    @Override
    void customerRole() {
    }

    private FirstLaunchRun run() {
        return runs.current().contextWrite(view -> RequestContexts.put(view,
            RequestContext.system(Locale.ENGLISH, "it"))).block().orElseThrow();
    }

    private JobRunner.Outcome advance() {
        round++;
        Instant scheduled = START.plus(Duration.ofHours(round)).truncatedTo(ChronoUnit.MINUTES);
        clock.set(scheduled.plusSeconds(1));
        JobRunner.Outcome outcome = jobs.run(job, scheduled);
        deliver();
        return outcome;
    }

    /** Runs the ready process directly as the system, under its own request id. */
    private int readyAsSystem(String userId, String requestId) {
        return processes.execute(ready, new FirstLaunchWaiting.ReadyInput(UUID.fromString(userId)))
            .contextWrite(view -> RequestContexts.put(view, RequestContext.system(Locale.ENGLISH, requestId)))
            .block().ran();
    }

    private void acceptAndBind(String email) {
        anonymous("/api/auth/invitation/confirm", Map.of("token", token(InvitationIT.INVITE_LINK, email),
            "password", OWN)).expectStatus().isNoContent();
        Map<String, Object> first = session(email, OWN, null);
        String challenge = (String) first.get("challenge");
        String secret = (String) post("/api/auth/challenge/enroll", null, Map.of("challenge", challenge))
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody().get("secret");
        post("/api/auth/challenge/enroll/confirm", null, Map.of("challenge", challenge, "code", totp(secret)))
            .expectStatus().isOk();
    }

    private static String userId(String email) {
        return String.valueOf(userOf(email).get("user_id"));
    }

    private void restartWith(Mode next) {
        mode = next;
        // What a start does in the waiting phase: correct addresses, or say why the configuration is not applied.
        revisions.onApplicationEvent(null);
    }

    private static void awaitOutput(CapturedOutput output, String text) throws InterruptedException {
        long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!output.getAll().contains(text)) {
            assertThat(System.nanoTime()).as("logged: " + text).isLessThan(end);
            Thread.sleep(20);
        }
    }

    @Test
    void stepsRunOnlyForAReadyAccountOfTheRunWithTheRunsConfigurationAndOnce(CapturedOutput output) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (runs.current().contextWrite(view -> RequestContexts.put(view,
                RequestContext.system(Locale.ENGLISH, "it"))).block()
            .map(found -> found.state() == FirstLaunchRun.State.RUNNING).orElse(true)) {
            assertThat(System.nanoTime()).isLessThan(end);
            Thread.sleep(50);
        }
        assertThat(advance()).isEqualTo(JobRunner.Outcome.SUCCEEDED);

        // An administrator gives Bob the configured role by hand before he has accepted: he is not ready.
        post("/api/processes/SEC_USER_ROLE_ASSIGN/latest", admin(), Map.of("userId", userId(B),
            "roleId", roleId("IT_STAFF"), "reason", "by hand")).expectStatus().isOk();
        assertThat(rolesOf(userId(B))).contains("IT_STAFF");
        assertThat(advance()).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        assertThat(readyAsSystem(userId(B), "fl-g-bob-early")).isZero();
        // Nor is a user who is not an account of the run.
        String stranger = createUserWithEmail(unique("fl-g-x"), address("fl-g-x"), null);
        assertThat(readyAsSystem(stranger, "fl-g-stranger")).isZero();
        assertThat(JOINED).isEmpty();

        // The configuration now is another shape (the accounts reordered: Alice's place holds Bob): it drives nothing.
        restartWith(Mode.CHANGED);
        awaitOutput(output, "While it differs, the application's steps for ready accounts do not run.");
        acceptAndBind(A);
        assertThat(advance()).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        assertThat(rolesOf(userId(A))).contains("IT_STAFF");
        assertThat(readyAsSystem(userId(A), "fl-g-alice-changed")).isZero();
        assertThat(JOINED).isEmpty();

        // The configuration missing: the same, and the start says so.
        restartWith(Mode.MISSING);
        awaitOutput(output, "the application's steps for ready accounts cannot run");
        assertThat(advance()).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        assertThat(readyAsSystem(userId(A), "fl-g-alice-missing")).isZero();
        assertThat(JOINED).isEmpty();
        assertThat(query("SELECT 1 FROM sys_first_launch_ready WHERE user_id = ?::uuid", userId(A))).isEmpty();

        // The run's configuration back: Alice's step runs once, replayed under two request ids.
        restartWith(Mode.AS_STARTED);
        assertThat(readyAsSystem(userId(A), "fl-g-alice-1")).isEqualTo(1);
        assertThat(readyAsSystem(userId(A), "fl-g-alice-2")).isZero();
        assertThat(advance()).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        assertThat(JOINED).containsExactly("system " + userId(A));

        // Bob becomes ready while the configuration is not the run's: not waited for, the run ends, the report says.
        restartWith(Mode.CHANGED);
        acceptAndBind(B);
        advance();
        advance();
        assertThat(run().state()).isEqualTo(FirstLaunchRun.State.DONE);
        assertThat(JOINED).hasSize(1);
        assertThat(text(to(A).getLast())).contains("#2 (user " + userId(B) + "): the application's steps for a"
            + " ready account did not run").contains("differs from the run's");

        // After the run's end nothing runs, the configuration as it was or not.
        restartWith(Mode.AS_STARTED);
        assertThat(readyAsSystem(userId(B), "fl-g-bob-after")).isZero();
        assertThat(JOINED).hasSize(1);
    }
}
