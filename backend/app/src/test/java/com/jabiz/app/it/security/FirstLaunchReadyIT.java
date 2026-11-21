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
import com.jabiz.runtime.firstlaunch.accounts.FirstLaunchWaiting;
import com.jabiz.runtime.firstlaunch.accounts.WaitingRole;
import com.jabiz.runtime.job.JobRunner;
import com.jabiz.runtime.security.SecurityEntities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The application's steps for an account once it is ready in the waiting phase ({@code whenAccountReady};
 * docs/design/23-first-launch.md section 9): run once its configured role is in effect, as the system, each once, as
 * sub-processes of {@code FIRST_LAUNCH_ACCOUNT_READY} under the job's item; a failing account does not hold up the
 * others or the run's end, and the report lists what did not run.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "jabiz.first-launch.await-interval=50ms", "jabiz.jobs.lock-at-least-for=0s", "jabiz.first-launch.ready-attempts=2",
    "jabiz.mail.enabled=true", "jabiz.mail.from=jabiz@example.com", "jabiz.mail.base-url=https://app.example.com",
    "spring.mail.host=localhost", "spring.mail.port=3047",
    "jabiz.security.entries.admin.accepted-roles=IT_STAFF," + WaitingRole.CODE})
@Import({SignUpItSupport.FixedTimeLimits.class, FirstLaunchReadyIT.Handler.class})
class FirstLaunchReadyIT extends SignUpItSupport {

    @RegisterExtension
    static final GreenMailExtension MAIL = new GreenMailExtension(new ServerSetup(3047, null, ServerSetup.PROTOCOL_SMTP));

    static final String A = "fl-r-a@example.com";
    static final String C = "fl-r-c@example.com";
    static final String OWN = "own horse battery";
    /** What the application's step saw: the actor, and the user ids it was given. */
    static final List<String> JOINED = new CopyOnWriteArrayList<>();
    /** What the content phase's acting step saw: the actor, and the user id the results gave by key. */
    static final List<String> ACTED = new CopyOnWriteArrayList<>();

    @DynamicPropertySource
    static void firstLaunch(DynamicPropertyRegistry registry) {
        String path = FirstLaunchWaitingIT.configFileIn("""
            version: 1
            site:
              language: en
            allowedEmailDomains: [example.com]
            accounts:
              - key: fl-r-alice
                email: %s
                name: Alice
                roles: [IT_STAFF]
                report: true
              - key: fl-r-carol
                email: %s
                name: Carol
                roles: [IT_STAFF]
            """.formatted(A, C));
        registry.add("jabiz.first-launch.config", () -> path);
    }

    public record Roles() {}

    public record Join(String member, String lead) {}

    public record Act(String self) {}

    @TestConfiguration
    static class Handler {

        @Bean
        ProcessDefinition<Roles, Boolean, ProcessContext> firstLaunchReadyItRoles() {
            return ProcessDefinition.define("FL_READY_IT_ROLES", 1, Roles.class, Boolean.class, ProcessContext.class,
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
        ProcessDefinition<Join, Boolean, ProcessContext> firstLaunchReadyItJoin() {
            return ProcessDefinition.define("FL_READY_IT_JOIN", 1, Join.class, Boolean.class, ProcessContext.class,
                pb -> pb
                    .permissions("test.first-launch")
                    .subProcessOnly()
                    .contextFactory((start, input) -> {
                        ProcessContext ctx = new ProcessContext(start);
                        ctx.put("in", input);
                        return ctx;
                    })
                    .compute("Join", (metadata, ctx) -> {
                        Join join = ctx.get("in", Join.class);
                        if (join.member().equals(join.lead())) {
                            throw new IllegalStateException("cannot join oneself");
                        }
                        JOINED.add(ctx.request().actorId() + " " + join.member() + " " + join.lead());
                    })
                    .outputMapper(ctx -> true));
        }

        @Bean
        ProcessDefinition<Act, Boolean, ProcessContext> firstLaunchReadyItAct() {
            return ProcessDefinition.define("FL_READY_IT_ACT", 1, Act.class, Boolean.class, ProcessContext.class,
                pb -> pb
                    .permissions("test.first-launch")
                    .subProcessOnly()
                    .contextFactory((start, input) -> {
                        ProcessContext ctx = new ProcessContext(start);
                        ctx.put("in", input);
                        return ctx;
                    })
                    .compute("Act", (metadata, ctx) -> ACTED.add(ctx.request().actorId() + " "
                        + ctx.get("in", Act.class).self()))
                    .outputMapper(ctx -> true));
        }

        @Bean
        FirstLaunchHandler firstLaunchReadyItHandler() {
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
                    // The platform keys the account steps by place; the configuration's key still finds them.
                    return List.of(FirstLaunchStep.run("roles", "FL_READY_IT_ROLES", results -> new Roles()),
                        FirstLaunchStep.run("act", "FL_READY_IT_ACT",
                            results -> new Act(results.userId("fl-r-alice"))).asAccount("fl-r-alice"));
                }

                @Override
                public Set<String> actingProcesses() {
                    return Set.of("FL_READY_IT_ACT");
                }

                @Override
                public Set<String> accountReadyProcesses() {
                    return Set.of("FL_READY_IT_JOIN");
                }

                @Override
                public List<FirstLaunchStep> whenAccountReady(FirstLaunchConfig config, FirstLaunchAccount account,
                    FirstLaunchResults results) {
                    // Alice joins Carol's group; Carol "joins" her own, which fails.
                    return List.of(FirstLaunchStep.run("join", "FL_READY_IT_JOIN", found -> new Join(
                        found.userId(account.key()), found.userId("fl-r-carol"))));
                }
            };
        }

        @Bean
        FirstLaunchExit firstLaunchReadyItExit() {
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

    @Test
    void theApplicationsStepsRunOnceForEachReadyAccountAndAFailureIsReported() throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (runs.current().contextWrite(view -> RequestContexts.put(view,
                RequestContext.system(Locale.ENGLISH, "it"))).block()
            .map(found -> found.state() == FirstLaunchRun.State.RUNNING).orElse(true)) {
            assertThat(System.nanoTime()).isLessThan(end);
            Thread.sleep(50);
        }
        // In the content phase a step acted as Alice, found by her key though the account's step is keyed by place.
        assertThat(ACTED).containsExactly(userId(A) + " " + userId(A));
        assertThat(query("SELECT request_id FROM op_process WHERE request_id LIKE 'first-launch-%'"))
            .extracting(row -> String.valueOf(row.get("request_id"))).noneMatch(id -> id.contains("fl-r-"))
            .anyMatch(id -> id.endsWith("-account:#1"));
        assertThat(advance()).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        // Invited, not accepted: not ready, nothing runs.
        assertThat(JOINED).isEmpty();
        acceptAndBind(A);
        // The role is given, and in the same round (its item comes after) Alice's step runs once, as the system.
        assertThat(advance()).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        assertThat(rolesOf(userId(A))).contains("IT_STAFF");
        assertThat(JOINED).containsExactly("system " + userId(A) + " " + userId(C));
        assertThat(advance()).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        assertThat(JOINED).as("once").hasSize(1);
        assertThat(query("SELECT step_key FROM sys_first_launch_ready WHERE user_id = ?::uuid", userId(A)))
            .extracting(row -> row.get("step_key")).containsExactly("join");
        assertThat(query("SELECT c.process_name AS child, p.process_name AS parent, g.process_name AS root, c.actor_id"
            + " FROM op_process c JOIN op_process p ON p.process_seq_id = c.parent_seq_id JOIN op_process g ON"
            + " g.process_seq_id = p.parent_seq_id WHERE c.process_name = 'FL_READY_IT_JOIN'")).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("parent", "FIRST_LAUNCH_ACCOUNT_READY")
                .containsEntry("root", "FIRST_LAUNCH_ADVANCE").containsEntry("actor_id", "system"));
        assertThat(run().state()).as("Carol has not accepted").isEqualTo(FirstLaunchRun.State.CONTENT_DONE);

        // Carol's step fails: her item fails, the others go on. A failure may pass (a conflict, a busy lock): the run
        // waits while the job tries it again, and ends without it once it has failed ready-attempts (2) times in a row.
        acceptAndBind(C);
        assertThat(advance()).isEqualTo(JobRunner.Outcome.PARTIAL);
        assertThat(rolesOf(userId(C))).as("her grant is in an item of its own").contains("IT_STAFF");
        assertThat(JOINED).as("not again").hasSize(1);
        assertThat(run().state()).as("one failure: still waiting").isEqualTo(FirstLaunchRun.State.CONTENT_DONE);
        for (int i = 0; i < 5 && run().state() != FirstLaunchRun.State.DONE; i++) {
            advance();
        }
        assertThat(run().state()).isEqualTo(FirstLaunchRun.State.DONE);
        assertThat(query("SELECT max(attempt) AS attempts FROM sys_job_item_failure WHERE item_key LIKE 'r%'"))
            .singleElement().satisfies(row -> assertThat(((Number) row.get("attempts")).intValue()).isEqualTo(2));
        assertThat(text(to(A).getLast())).contains("#2 (user " + userId(C) + "): the application's steps for a ready"
            + " account did not run").contains(": join");
        assertThat(JOINED).hasSize(1);
    }
}
