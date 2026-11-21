package com.jabiz.app.it.security;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;
import com.jabiz.app.it.fixture.SqlStatementLog;
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
import com.jabiz.runtime.firstlaunch.accounts.FirstLaunchWaiting;
import com.jabiz.runtime.firstlaunch.accounts.GrantPlan;
import com.jabiz.runtime.firstlaunch.accounts.WaitingRole;
import com.jabiz.runtime.job.JobRunner;
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.runtime.test.TestTokens;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The first launch hands over to the administrators (plan F-LAUNCH section 5.3; decision D82 review M3-M5): once two
 * administrators exist the setup window ends, and a protected role granted later waits for their approval
 * ({@code PENDING}); a grant they reject is theirs and not asked for again; a disabled account gets nothing more. All of
 * it is listed in the report, and nothing the first launch writes is ever updated or deleted.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "jabiz.first-launch.await-interval=50ms", "jabiz.jobs.lock-at-least-for=0s", "it.sql-log.enabled=true",
    "jabiz.mail.enabled=true", "jabiz.mail.from=jabiz@example.com", "jabiz.mail.base-url=https://app.example.com",
    "spring.mail.host=localhost", "spring.mail.port=3046",
    "jabiz.security.entries.admin.accepted-roles=IT_STAFF,IT_SUPER,IT_GUARD," + WaitingRole.CODE})
@Import({SignUpItSupport.FixedTimeLimits.class, FirstLaunchHandoverIT.Handler.class, SqlStatementLog.class})
class FirstLaunchHandoverIT extends SignUpItSupport {

    @RegisterExtension
    static final GreenMailExtension MAIL = new GreenMailExtension(new ServerSetup(3046, null, ServerSetup.PROTOCOL_SMTP));

    static final String A = "fl-h-a@example.com";
    static final String C = "fl-h-c@example.com";
    static final String B = "fl-h-b@example.com";
    static final String D = "fl-h-d@example.com";
    static final String E = "fl-h-e@example.com";
    static final String F = "fl-h-f@example.com";
    static final String DAVE = "Dave Qx7Handover";
    static final String OWN = "own horse battery";
    static final AtomicReference<String> CONFIRMED = new AtomicReference<>("");

    @DynamicPropertySource
    static void firstLaunch(DynamicPropertyRegistry registry) {
        String path = FirstLaunchWaitingIT.configFileIn("""
            version: 1
            site:
              language: en
            allowedEmailDomains: [example.com]
            accounts:
              - key: fl-h-alice
                email: %s
                name: Alice
                roles: [IT_SUPER]
                report: true
              - key: fl-h-carol
                email: %s
                name: Carol
                roles: [IT_SUPER]
              - key: fl-h-bob
                email: %s
                name: Bob
                roles: [IT_GUARD]
              - key: fl-h-dave
                email: %s
                name: %s
                roles: [IT_STAFF]
              - key: fl-h-erin
                email: %s
                name: Erin
                roles: [IT_STAFF]
              - key: fl-h-fred
                email: %s
                name: Fred
                roles: [IT_STAFF]
            """.formatted(A, C, B, D, DAVE, E, F));
        registry.add("jabiz.first-launch.config", () -> path);
        // The tests' login is the maintenance login too (as ErasureIT): the purge overwrites the append-only rows.
        registry.add("jabiz.erasure.datasource.url", () -> DB.r2dbcUrl() + "?schema=" + schema());
        registry.add("jabiz.erasure.datasource.username", DB::username);
        registry.add("jabiz.erasure.datasource.password", DB::password);
        registry.add("jabiz.first-launch.confirm-admins", CONFIRMED::get);
    }

    public record Roles() {}

    @TestConfiguration
    static class Handler {

        @Bean
        ProcessDefinition<Roles, Boolean, ProcessContext> firstLaunchHandoverItRoles() {
            return ProcessDefinition.define("FL_HANDOVER_IT_ROLES", 1, Roles.class, Boolean.class,
                ProcessContext.class, pb -> pb
                    .permissions("test.first-launch")
                    .contextFactory((start, input) -> new ProcessContext(start))
                    .compute("Roles", (metadata, ctx) -> {
                        for (Map.Entry<String, String> role : Map.of("IT_STAFF", "price.read", "IT_SUPER", "*",
                            "IT_GUARD", "ledger.post").entrySet()) {
                            Object id = ctx.changes().insert(SecurityEntities.ROLE, Map.of("roleCode", role.getKey(),
                                "labels", Map.of("en", role.getKey()), "enabled", true));
                            ctx.changes().insert(SecurityEntities.ROLE_PERMISSION, Map.of("roleId", id,
                                "permission", role.getValue()));
                        }
                    })
                    .outputMapper(ctx -> true));
        }

        @Bean
        FirstLaunchHandler firstLaunchHandoverItHandler() {
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
                    return List.of(FirstLaunchStep.run("roles", "FL_HANDOVER_IT_ROLES", results -> new Roles()));
                }
            };
        }

        @Bean
        FirstLaunchExit firstLaunchHandoverItExit() {
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

    @org.junit.jupiter.api.BeforeEach
    void maintenanceRole() {
        execute("DO $$ BEGIN CREATE ROLE jabiz_maintenance; EXCEPTION WHEN duplicate_object THEN NULL; END $$");
        execute("DO $$ BEGIN EXECUTE 'GRANT jabiz_maintenance TO ' || quote_ident(current_user);"
            + " EXCEPTION WHEN OTHERS THEN NULL; END $$");
    }

    @Autowired
    com.jabiz.runtime.process.ProcessExecutor executor;

    /** Every table and text or JSON column of the schema that holds the value (as ErasureIT). */
    private static List<String> occurrences(String needle) {
        List<String> found = new java.util.ArrayList<>();
        for (Map<String, Object> column : query("SELECT table_name::text AS t, column_name::text AS c"
            + " FROM information_schema.columns WHERE table_schema = current_schema()"
            + " AND data_type IN ('text', 'character varying', 'character', 'jsonb', 'json')")) {
            String table = (String) column.get("t");
            String name = (String) column.get("c");
            String sql = "SELECT count(*) AS n FROM \"" + table + "\" WHERE \"" + name + "\"::text ILIKE ?";
            if (((Number) query(sql, "%" + needle + "%").getFirst().get("n")).longValue() > 0) {
                found.add(table + "." + name);
            }
        }
        return found;
    }

    private FirstLaunchRun run() {
        return runs.current().contextWrite(view -> RequestContexts.put(view,
            RequestContext.system(Locale.ENGLISH, "it"))).block().orElseThrow();
    }

    private void advance() {
        round++;
        Instant scheduled = START.plus(Duration.ofMinutes(round)).truncatedTo(ChronoUnit.MINUTES);
        clock.set(scheduled.plusSeconds(1));
        assertThat(jobs.run(job, scheduled)).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        deliver();
    }

    private String acceptAndBind(String email) {
        anonymous("/api/auth/invitation/confirm", Map.of("token", token(InvitationIT.INVITE_LINK, email),
            "password", OWN)).expectStatus().isNoContent();
        Map<String, Object> first = session(email, OWN, null);
        String challenge = (String) first.get("challenge");
        String secret = (String) post("/api/auth/challenge/enroll", null, Map.of("challenge", challenge))
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody().get("secret");
        post("/api/auth/challenge/enroll/confirm", null, Map.of("challenge", challenge, "code", totp(secret)))
            .expectStatus().isOk();
        Instant bound = ((java.sql.Timestamp) query("SELECT confirmed_time FROM sec_user_mfa_version WHERE user_id ="
            + " ?::uuid AND confirmed ORDER BY version_no DESC LIMIT 1", userId(email)).getFirst()
            .get("confirmed_time")).toInstant();
        return GrantPlan.fingerprint(run().runId().toString(), UUID.fromString(userId(email)), bound);
    }

    private static String userId(String email) {
        return String.valueOf(userOf(email).get("user_id"));
    }

    private static List<Map<String, Object>> changesOf(String email) {
        return query("SELECT DISTINCT ON (change_id) change_id::text AS id, status FROM sec_protected_change_version"
            + " WHERE user_id = ? ORDER BY change_id, version_no DESC", userId(email));
    }

    @Test
    void afterTheSetupWindowGrantsAreHandedOverAndTheAdministratorsDecide() throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (runs.current().contextWrite(view -> RequestContexts.put(view,
                RequestContext.system(Locale.ENGLISH, "it"))).block()
            .map(found -> found.state() == FirstLaunchRun.State.RUNNING).orElse(true)) {
            assertThat(System.nanoTime()).isLessThan(end);
            Thread.sleep(50);
        }
        advance();
        assertThat(to(D)).hasSize(1);

        // Two administrators: the setup window ends.
        String alice = acceptAndBind(A);
        String carol = acceptAndBind(C);
        CONFIRMED.set(alice + "," + carol);
        advance();
        assertThat(rolesOf(userId(A))).contains("IT_SUPER");
        assertThat(rolesOf(userId(C))).contains("IT_SUPER");
        // The end of the window is recorded by the next write of security (here: the waiting roles' withdrawal).
        advance();
        assertThat(rolesOf(userId(A))).containsExactly("IT_SUPER");
        assertThat(query("SELECT count(*) AS n FROM sec_protected_grants_armed").getFirst().get("n")).isEqualTo(1L);

        // Dave's account is disabled (someone saw something wrong): nothing more for it. Fred's address was changed by
        // an administrator before he accepted: no invitation may go there (D63), so the first launch leaves him to
        // people rather than fail every minute.
        updateUser(userId(D), Map.of("enabled", false));
        String fred = userId(F);
        updateUser(fred, Map.of("email", "fl-h-fred2@example.com"));

        // Bob's protected role now waits for another administrator's approval: handed over, asked once.
        acceptAndBind(B);
        advance();
        assertThat(changesOf(B)).singleElement().satisfies(row -> assertThat(row).containsEntry("status", "PENDING"));
        assertThat(rolesOf(userId(B))).containsExactly(WaitingRole.CODE);
        // Fred is past the reminder time: not invited again (that would be refused), and nothing fails.
        clock.set(START.plus(Duration.ofDays(4)));
        round += 4 * 24 * 60;
        advance();
        assertThat(to("fl-h-fred2@example.com")).isEmpty();
        assertThat(changesOf(B)).hasSize(1);
        assertThat(run().state()).isEqualTo(FirstLaunchRun.State.CONTENT_DONE);

        // Alice rejects it: theirs, not asked for again; the run is done.
        String request = String.valueOf(query("SELECT request_id::text AS id FROM sys_approval_request_version WHERE"
            + " entity_id::text = ? ORDER BY version_no DESC LIMIT 1", changesOf(B).getFirst().get("id"))
            .getFirst().get("id"));
        post("/api/processes/APPROVAL_DECIDE/latest", TestTokens.bearer(tokens, userId(A), "*"),
            new LinkedHashMap<>(Map.of("requestId", request, "decision", "REJECT", "reason", "not this role")))
            .expectStatus().isOk();
        deliver();
        assertThat(changesOf(B)).singleElement().satisfies(row -> assertThat(row.get("status")).isNotEqualTo("PENDING"));
        advance();
        assertThat(changesOf(B)).as("not asked for again").hasSize(1);
        assertThat(run().state()).as("Erin has not accepted yet").isEqualTo(FirstLaunchRun.State.CONTENT_DONE);

        acceptAndBind(E);
        advance();
        advance();
        assertThat(changesOf(B)).hasSize(1);
        assertThat(rolesOf(userId(E))).containsExactly("IT_STAFF");
        assertThat(run().state()).isEqualTo(FirstLaunchRun.State.DONE);
        String report = text(to(A).getLast());
        assertThat(report).contains("#3: IT_GUARD was decided by the administrators")
            .contains("#4 (user " + userId(D) + ") is disabled")
            .contains("#6 (user " + fred + ") is disabled, or someone else changed its address")
            .contains("setup window of protected grants has ended")
            .doesNotContain("Alice").doesNotContain("fl-h-bob").doesNotContain(B);
        assertThat(rolesOf(userId(D))).containsExactly(WaitingRole.CODE);
        assertThat(query("SELECT enabled FROM sec_role_version WHERE role_code = ? ORDER BY version_no DESC LIMIT 1",
            WaitingRole.CODE).getFirst()).containsEntry("enabled", false);

        // Nothing of the first launch is ever updated or deleted.
        assertThat(List.copyOf(SqlStatementLog.STATEMENTS)).noneMatch(sql -> sql.matches(
            "(?is).*\\b(UPDATE|DELETE\\s+FROM)\\s+(\\w+_version|sys_first_launch_\\w+)\\b.*"));
        // No account key (a user name) in the request ids or the first launch's own operations.
        assertThat(query("SELECT request_id FROM op_process")).allSatisfy(row ->
            assertThat(String.valueOf(row.get("request_id"))).doesNotContain("fl-h-"));
        assertThat(query("SELECT input_summary::text AS s FROM op_process WHERE process_name LIKE 'FIRST_LAUNCH_%'"
            + " AND process_name NOT IN ('FIRST_LAUNCH_ACCOUNT_CREATE')")).allSatisfy(row ->
            assertThat(String.valueOf(row.get("s"))).doesNotContain("fl-h-"));

        // A first launch account erased: nothing of it is left anywhere - its key (the user name) was never recorded
        // with the operations, the run's records hold none of it.
        String dave = userId(D);
        // The search finds them before: it would see them after.
        for (String needle : List.of("fl-h-dave", D, DAVE)) {
            assertThat(occurrences(needle)).as("where %s is before", needle).isNotEmpty();
        }
        AtomicReference<String> erasure = new AtomicReference<>();
        com.jabiz.runtime.erasure.AccountErasure.EraseOutput erased = executor.execute(
                com.jabiz.runtime.erasure.AccountErasure.ERASE,
                new com.jabiz.runtime.erasure.AccountErasure.EraseInput(UUID.fromString(dave), "it-support"))
            .contextWrite(view -> RequestContexts.put(view, RequestContext.system(Locale.ENGLISH, "it-erase")))
            .block();
        erasure.set(erased.erasureId());
        deliver();
        assertThat(query("SELECT row_count FROM sys_erasure_purge WHERE erasure_id = ?::uuid", erasure.get()))
            .isNotEmpty();
        for (String needle : List.of("fl-h-dave", D, DAVE)) {
            assertThat(occurrences(needle)).as("where %s still is", needle).isEmpty();
        }

        // The run's records hold no key or address.
        assertThat(query("SELECT detail::text AS d FROM sys_first_launch_event")).allSatisfy(row ->
            assertThat(String.valueOf(row.get("d"))).doesNotContain("fl-h-").doesNotContain("@"));
        assertThat(query("SELECT * FROM sys_first_launch_account")).allSatisfy(row ->
            assertThat(row.values().toString()).doesNotContain("fl-h-"));
    }
}
