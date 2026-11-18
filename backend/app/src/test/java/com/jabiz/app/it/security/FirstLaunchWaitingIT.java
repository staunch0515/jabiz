package com.jabiz.app.it.security;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;
import com.jabiz.context.RequestContext;
import com.jabiz.firstlaunch.FirstLaunchConfig;
import com.jabiz.firstlaunch.FirstLaunchHandler;
import com.jabiz.firstlaunch.FirstLaunchProblem;
import com.jabiz.firstlaunch.FirstLaunchStep;
import com.jabiz.job.JobDefinition;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.firstlaunch.FirstLaunchExit;
import com.jabiz.runtime.firstlaunch.FirstLaunchRun;
import com.jabiz.runtime.firstlaunch.FirstLaunchRuns;
import com.jabiz.runtime.firstlaunch.FirstLaunchConfigReader;
import com.jabiz.runtime.firstlaunch.FirstLaunchSettings;
import com.jabiz.runtime.firstlaunch.accounts.FirstLaunchRevisions;
import com.jabiz.runtime.firstlaunch.accounts.FirstLaunchWaiting;
import com.jabiz.runtime.firstlaunch.accounts.GrantPlan;
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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The first launch's waiting phase end to end (docs/design/23-first-launch.md section 8; plan F-LAUNCH sections 5,
 * 6.5): the accounts are created in the content phase without a password and invited only once it is done; each gets
 * its configured roles once it accepted the invitation and bound a second factor - an administrator's role only after
 * the operator confirmed its fingerprint; the waiting role is withdrawn once a configured role is in effect; then the
 * run ends DONE, the waiting role is disabled for good and the report goes to the report account.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "jabiz.first-launch.await-interval=50ms", "jabiz.jobs.lock-at-least-for=0s",
    "jabiz.mail.enabled=true", "jabiz.mail.from=jabiz@example.com", "jabiz.mail.base-url=https://app.example.com",
    "spring.mail.host=localhost", "spring.mail.port=3044",
    "jabiz.security.entries.admin.accepted-roles=IT_STAFF,IT_SUPER," + WaitingRole.CODE})
@Import({SignUpItSupport.FixedTimeLimits.class, FirstLaunchWaitingIT.Handler.class})
class FirstLaunchWaitingIT extends SignUpItSupport {

    @RegisterExtension
    static final GreenMailExtension MAIL = new GreenMailExtension(new ServerSetup(3044, null, ServerSetup.PROTOCOL_SMTP));

    static final String A = "fl-a@example.com";
    static final String B = "fl-b@example.com";
    static final String B_WRONG = "fl-b-wrong@example.com";
    static final String OWN = "own horse battery";
    /** What the operator confirmed (JABIZ_FIRST_LAUNCH_CONFIRM_ADMINS), read anew at every use. */
    static final AtomicReference<String> CONFIRMED = new AtomicReference<>("");

    @DynamicPropertySource
    static void firstLaunch(DynamicPropertyRegistry registry) {
        registry.add("jabiz.first-launch.config", () -> configFile(config(A, B_WRONG)));
        registry.add("jabiz.first-launch.confirm-admins", CONFIRMED::get);
    }

    /** The configuration, with the accounts' addresses given. */
    static String config(String a, String b) {
        return """
            version: 1
            site:
              language: en
            allowedEmailDomains: [example.com]
            accounts:
              - key: A
                email: %s
                name: Alice Admin
                language: en
                roles: [IT_STAFF, IT_SUPER]
                report: true
              - key: B
                email: %s
                name: Bob Staff
                roles: [IT_STAFF]
            """.formatted(a, b);
    }

    static Path mainFile;

    /** A configuration file of its own, for another test class. */
    static String configFileIn(String content) {
        try {
            Path main = Files.createTempDirectory("first-launch").resolve("first-launch.yml");
            Files.writeString(main, content);
            return main.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String configFile(String content) {
        try {
            if (mainFile == null) {
                mainFile = Files.createTempDirectory("first-launch").resolve("first-launch.yml");
            }
            Files.writeString(mainFile, content);
            return mainFile.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public record Roles() {}

    /** The application's part: its roles, as the application's setup would create them. */
    @TestConfiguration
    static class Handler {

        @Bean
        ProcessDefinition<Roles, Boolean, ProcessContext> firstLaunchWaitingItRoles() {
            return ProcessDefinition.define("FL_WAIT_IT_ROLES", 1, Roles.class, Boolean.class, ProcessContext.class,
                pb -> pb
                    .permissions("test.first-launch")
                    .contextFactory((start, input) -> new ProcessContext(start))
                    .compute("Roles", (metadata, ctx) -> {
                        Object staff = ctx.changes().insert(SecurityEntities.ROLE, Map.of("roleCode", "IT_STAFF",
                            "labels", Map.of("en", "Staff"), "enabled", true));
                        ctx.changes().insert(SecurityEntities.ROLE_PERMISSION, Map.of("roleId", staff,
                            "permission", "price.read"));
                        Object admin = ctx.changes().insert(SecurityEntities.ROLE, Map.of("roleCode", "IT_SUPER",
                            "labels", Map.of("en", "Super"), "enabled", true));
                        ctx.changes().insert(SecurityEntities.ROLE_PERMISSION, Map.of("roleId", admin,
                            "permission", "*"));
                    })
                    .outputMapper(ctx -> true));
        }

        @Bean
        FirstLaunchHandler firstLaunchWaitingItHandler() {
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
                    return List.of(FirstLaunchStep.run("roles", "FL_WAIT_IT_ROLES", results -> new Roles()));
                }

                @Override
                public List<String> remaining(FirstLaunchConfig config) {
                    return List.of("Publish the legal texts.");
                }
            };
        }

        @Bean
        FirstLaunchExit firstLaunchWaitingItExit() {
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
    FirstLaunchRevisions revisions;

    @Autowired
    @Qualifier("firstLaunchJob")
    JobDefinition<FirstLaunchWaiting.AdvanceInput> job;

    private static int round;

    @Override
    GreenMailExtension mail() {
        return MAIL;
    }

    /** Not here: the application does not serve while the content phase runs, and needs no customer role. */
    @Override
    void customerRole() {
    }

    private FirstLaunchRun run() {
        RequestContext system = RequestContext.system(Locale.ENGLISH, "it");
        return runs.current().contextWrite(view -> RequestContexts.put(view, system)).block().orElseThrow();
    }

    private void contentDone() {
        long end = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (runs.current().contextWrite(view -> RequestContexts.put(view,
                RequestContext.system(Locale.ENGLISH, "it"))).block()
            .map(found -> found.state() == FirstLaunchRun.State.RUNNING).orElse(true)) {
            if (System.nanoTime() > end) {
                throw new AssertionError("The content phase did not end");
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
        assertThat(run().state()).isEqualTo(FirstLaunchRun.State.CONTENT_DONE);
    }

    /** One run of the job, at a scheduled time of its own; then the mail it recorded goes out. */
    private void advance() {
        round++;
        Instant scheduled = START.plus(Duration.ofMinutes(round)).truncatedTo(ChronoUnit.MINUTES);
        clock.set(scheduled.plusSeconds(1));
        assertThat(jobs.run(job, scheduled)).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        deliver();
    }

    private void acceptAndBind(String email) {
        anonymous("/api/auth/invitation/confirm", Map.of("token", token(InvitationIT.INVITE_LINK, email),
            "password", OWN)).expectStatus().isNoContent();
        Map<String, Object> first = session(email, OWN, null);
        assertThat(first).containsEntry("status", "MFA_ENROLLMENT_REQUIRED");
        String challenge = (String) first.get("challenge");
        String secret = (String) post("/api/auth/challenge/enroll", null, Map.of("challenge", challenge))
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody().get("secret");
        post("/api/auth/challenge/enroll/confirm", null, Map.of("challenge", challenge, "code", totp(secret)))
            .expectStatus().isOk();
    }

    /** The configuration as it is on disk now. */
    private static FirstLaunchConfig onDisk() {
        FirstLaunchConfigReader.Read read = new FirstLaunchConfigReader(new FirstLaunchSettings(mainFile.toString(),
            false, Duration.ofMinutes(10), Duration.ofSeconds(1), 1 << 20, 1 << 24, 100)).read();
        assertThat(read.problems()).isEmpty();
        return read.config();
    }

    private static long revised() {
        return (Long) query("SELECT count(*) AS n FROM sys_first_launch_event WHERE kind = 'REVISED'").getFirst()
            .get("n");
    }

    private static String userId(String email) {
        return String.valueOf(userOf(email).get("user_id"));
    }

    private static boolean holds(String email, String role) {
        return rolesOf(userId(email)).contains(role);
    }

    @Test
    void theAccountsAreInvitedAfterTheContentAndGetTheirRolesOnceTheyBoundASecondFactor() throws Exception {
        contentDone();
        // Created in the content phase, without a password, holding the waiting role; nobody invited yet.
        assertThat(userOf(A)).containsEntry("password_hash", null);
        assertThat(rolesOf(userId(A))).containsExactly(WaitingRole.CODE);
        deliver();
        assertThat(to(A)).isEmpty();
        assertThat(query("SELECT account_key FROM sys_first_launch_account ORDER BY position"))
            .extracting(row -> row.get("account_key")).containsExactly("A", "B");

        // The job invites both, once.
        advance();
        assertThat(to(A)).hasSize(1);
        assertThat(to(B_WRONG)).hasSize(1);
        advance();
        assertThat(to(A)).hasSize(1);
        String wrongLink = token(InvitationIT.INVITE_LINK, B_WRONG);

        // B's address was mistyped: corrected in the configuration and restarted (decision D83).
        assertThat(revisions.revise(onDisk())).as("nothing changed yet").isEmpty();
        configFile(config(A, B));
        assertThat(revisions.revise(onDisk())).containsExactly("B");
        deliver();
        assertThat(to(B)).hasSize(1);
        assertThat(refusal(anonymous("/api/auth/invitation/confirm", Map.of("token", wrongLink, "password", OWN)),
            422)).isEqualTo("TOKEN_INVALID");
        assertThat(revised()).isEqualTo(1);
        assertThat(revisions.revise(onDisk())).as("applied already").isEmpty();
        assertThat(revised()).isEqualTo(1);
        advance();
        assertThat(to(B)).as("not invited again by the job").hasSize(1);
        // Anything else that differs: nothing is applied.
        configFile(config(A, B).replace("Bob Staff", "Robert Staff").replace(B, "fl-b2@example.com"));
        assertThat(revisions.revise(onDisk())).isEmpty();
        assertThat(revised()).isEqualTo(1);
        configFile(config(A, B));

        // Accepting alone gives nothing; with a second factor bound, the ordinary role is given.
        anonymous("/api/auth/invitation/confirm", Map.of("token", token(InvitationIT.INVITE_LINK, B),
            "password", OWN)).expectStatus().isNoContent();
        advance();
        assertThat(rolesOf(userId(B))).containsExactly(WaitingRole.CODE);
        Map<String, Object> first = session(B, OWN, null);
        String challenge = (String) first.get("challenge");
        String secret = (String) post("/api/auth/challenge/enroll", null, Map.of("challenge", challenge))
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody().get("secret");
        post("/api/auth/challenge/enroll/confirm", null, Map.of("challenge", challenge, "code", totp(secret)))
            .expectStatus().isOk();
        advance();
        assertThat(rolesOf(userId(B))).containsExactlyInAnyOrder("IT_STAFF", WaitingRole.CODE);
        // The waiting role goes once a configured role is in effect.
        advance();
        assertThat(rolesOf(userId(B))).containsExactly("IT_STAFF");
        assertThat(query("SELECT DISTINCT o.process_name, o.actor_id FROM sec_user_role_version v JOIN op_process o"
            + " ON o.process_seq_id = v.process_seq_id WHERE v.user_id = ?::uuid AND o.process_name ="
            + " 'SEC_USER_ROLE_ASSIGN'", userId(B))).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("actor_id", "system"));

        // The administrator: the ordinary role at once, the administrator's only after the operator's confirmation.
        acceptAndBind(A);
        advance();
        assertThat(holds(A, "IT_STAFF")).isTrue();
        assertThat(holds(A, "IT_SUPER")).isFalse();
        // An accepted account's address is no longer corrected from the configuration.
        configFile(config("fl-a2@example.com", B));
        assertThat(revisions.revise(onDisk())).isEmpty();
        assertThat(userOf(A)).isNotNull();
        assertThat(revised()).isEqualTo(1);
        configFile(config(A, B));
        advance();
        assertThat(holds(A, "IT_SUPER")).as("no confirmation yet").isFalse();
        assertThat(run().state()).isEqualTo(FirstLaunchRun.State.CONTENT_DONE);
        Instant bound = ((java.sql.Timestamp) query("SELECT confirmed_time FROM sec_user_mfa_version WHERE user_id = ?::uuid"
            + " AND confirmed ORDER BY version_no DESC LIMIT 1", userId(A)).getFirst().get("confirmed_time")).toInstant();
        String fingerprint = GrantPlan.fingerprint(run().runId().toString(), UUID.fromString(userId(A)), bound);
        CONFIRMED.set("000000000000," + fingerprint.toUpperCase(Locale.ROOT));
        advance();
        assertThat(holds(A, "IT_SUPER")).isTrue();

        // Done: the waiting role disabled for good, the report mailed to the report account.
        assertThat(run().state()).isEqualTo(FirstLaunchRun.State.DONE);
        assertThat(query("SELECT enabled FROM sec_role_version WHERE role_code = ? ORDER BY version_no DESC LIMIT 1",
            WaitingRole.CODE).getFirst()).containsEntry("enabled", false);
        assertThat(to(A).getLast().getSubject()).isEqualTo("First launch report");
        assertThat(text(to(A).getLast())).contains("regular operation").contains("IT_SUPER APPLIED")
            .contains("Publish the legal texts.").contains("JABIZ_FIRST_LAUNCH_");
        assertThat(to(B)).hasSize(1);
        Map<String, Object> done = query("SELECT detail::text AS detail FROM sys_first_launch_event WHERE kind ="
            + " 'DONE'").getFirst();
        assertThat(String.valueOf(done.get("detail"))).contains("\"IT_SUPER\": \"APPLIED\"").doesNotContain(A);

        // Nothing more to do.
        int before = to(A).size();
        advance();
        assertThat(to(A)).hasSize(before);
    }
}
