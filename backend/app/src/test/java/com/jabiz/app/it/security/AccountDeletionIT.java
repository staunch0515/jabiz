package com.jabiz.app.it.security;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;
import com.jabiz.app.commerce.CommerceEntities;
import com.jabiz.job.JobDefinition;
import com.jabiz.runtime.erasure.AccountDeletionProcesses;
import com.jabiz.runtime.job.JobRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asking for the deletion of an account (docs/design/10-security.md section 17.5; decision D62 item 2): signed in at
 * the portal (proof of who one is, the application's notices), from a mailed link without the application, or
 * scheduled by an administrator; withdrawn before it is due; carried out by the daily job once the cooling-off period
 * is over; refused for holders of protected permissions and held while a legal hold lasts.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "jabiz.mail.enabled=true", "jabiz.mail.from=jabiz@example.com", "jabiz.mail.base-url=https://app.example.com",
    "spring.mail.host=localhost", "spring.mail.port=3041", SignUpItSupport.ENTRY_PROPERTIES_REGISTRATION,
    SignUpItSupport.ENTRY_PROPERTIES_ADMIN, SignUpItSupport.ENTRY_PROPERTIES_LOOSE,
    "jabiz.security.entries.portal.account-deletion=true",
    // The job runs more than once a test: its lock is not kept past a run.
    "jabiz.jobs.lock-at-least-for=0s"})
@Import(SignUpItSupport.FixedTimeLimits.class)
class AccountDeletionIT extends SignUpItSupport {

    @RegisterExtension
    static final GreenMailExtension MAIL = new GreenMailExtension(new ServerSetup(3041, null, ServerSetup.PROTOCOL_SMTP));

    static final Pattern LINK = Pattern.compile(
        "https://app\\.example\\.com/portal/auth/account-deletion\\?entry=(portal)&token=([A-Za-z0-9_-]+)");

    @DynamicPropertySource
    static void maintenance(DynamicPropertyRegistry registry) {
        // The tests' login is the maintenance login too: a member of jabiz_maintenance (granted below).
        registry.add("jabiz.erasure.datasource.url", () -> DB.r2dbcUrl() + "?schema=" + schema());
        registry.add("jabiz.erasure.datasource.username", DB::username);
        registry.add("jabiz.erasure.datasource.password", DB::password);
    }

    @Autowired
    JobRunner jobs;

    @Autowired
    com.jabiz.runtime.security.SignInEntries entries;

    @Autowired
    com.jabiz.runtime.dataset.DatasetRegistry datasets;

    @Autowired
    com.jabiz.runtime.entity.EntityDefinitionRegistry definitions;

    @Autowired
    JobDefinition<AccountDeletionProcesses.ExecuteInput> accountDeletionJob;

    @Override
    GreenMailExtension mail() {
        return MAIL;
    }

    @BeforeEach
    void maintenanceRole() {
        execute("DO $$ BEGIN CREATE ROLE jabiz_maintenance; EXCEPTION WHEN duplicate_object THEN NULL; END $$");
        execute("DO $$ BEGIN EXECUTE 'GRANT jabiz_maintenance TO ' || quote_ident(current_user);"
            + " EXCEPTION WHEN OTHERS THEN NULL; END $$");
    }

    @Test
    void aRequestInThePortalWaitsOutTheCoolingOffAndTheJobErasesTheAccount() {
        String email = address("leaver");
        String userId = customer(email);
        insert(CommerceEntities.CUSTOMER_DATASET, Map.of("customerCode", unique("C"), "customerName",
            "Leaver " + userId, "userId", userId), null);
        String bearer = bearerOf(portal(email));

        // The state first: open here, nothing pending, the application's notice to read.
        Map<String, Object> status = status(bearer);
        assertThat(status).containsEntry("available", true).containsEntry("pending", null);
        assertThat(notices(status)).extracting(n -> n.get("code")).containsExactly("commerce.orders-kept");

        // Proof of who one is, then the notices.
        assertThat(refusal(ask(bearer, null, List.of()), 422)).isEqualTo("REAUTHENTICATION_REQUIRED");
        assertThat(refusal(ask(bearer, "not the password", List.of()), 422)).isEqualTo("PASSWORD_INCORRECT");
        Map<String, Object> unread = ask(bearer, PASSWORD, List.of()).expectStatus().isEqualTo(422).expectBody(MAP)
            .returnResult().getResponseBody();
        assertThat(violations(unread)).extracting(v -> v.get("ruleCode"))
            .containsExactly("ACCOUNT_DELETION_NOTICES", "erasure.notice.commerce.orders-kept");
        assertThat(query("SELECT * FROM sys_account_deletion WHERE user_id = ?::uuid", userId)).isEmpty();

        Instant asked = clock.instant();
        Map<String, Object> requested = ask(bearer, PASSWORD, List.of("commerce.orders-kept")).expectStatus().isOk()
            .expectBody(MAP).returnResult().getResponseBody();
        assertThat(Instant.parse((String) requested.get("dueTime"))).isEqualTo(asked.plus(Duration.ofDays(14)));
        assertThat(refusal(ask(bearer, PASSWORD, List.of("commerce.orders-kept")), 422))
            .isEqualTo("ACCOUNT_DELETION_PENDING");
        assertThat(status(bearer).get("pending")).isNotNull();
        deliver();
        assertThat(text(to(email).getLast())).contains("will be deleted on "
            + asked.plus(Duration.ofDays(14)).toString().substring(0, 10));

        // The account works as before meanwhile, and the job leaves the request alone until it is due.
        get("/api/auth/me", bearer).expectStatus().isOk();
        clock.advance(Duration.ofDays(13));
        assertThat(jobs.run(accountDeletionJob, clock.instant())).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        assertThat(query("SELECT * FROM sys_account_deletion_end e JOIN sys_account_deletion d USING (request_id)"
            + " WHERE d.user_id = ?::uuid", userId)).isEmpty();

        clock.advance(Duration.ofDays(1).plusHours(1));
        assertThat(jobs.run(accountDeletionJob, clock.instant())).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        Map<String, Object> ended = query("SELECT e.outcome, e.erasure_id FROM sys_account_deletion_end e"
            + " JOIN sys_account_deletion d USING (request_id) WHERE d.user_id = ?::uuid", userId).getFirst();
        assertThat(ended.get("outcome")).isEqualTo("ERASED");
        assertThat(query("SELECT erased_at, enabled FROM sec_user_version WHERE user_id = ?::uuid ORDER BY version_no"
            + " DESC LIMIT 1", userId).getFirst()).containsEntry("enabled", false)
            .satisfies(user -> assertThat(user.get("erased_at")).isNotNull());
        // The purge follows the erasure's event.
        deliver();
        assertThat(query("SELECT * FROM sys_erasure_purge WHERE erasure_id = ?::uuid",
            String.valueOf(ended.get("erasure_id")))).hasSize(1);
        assertThat(refusal(loginTo(email, PASSWORD, PORTAL), 401)).isNotNull();
    }

    @Test
    void aWithdrawnRequestIsNeverCarriedOut() {
        String email = address("doubter");
        String userId = customer(email);
        String bearer = bearerOf(portal(email));
        assertThat(refusal(withdraw(bearer), 422)).isEqualTo("ACCOUNT_DELETION_NONE");
        ask(bearer, PASSWORD, List.of()).expectStatus().isOk();

        assertThat(withdraw(bearer).expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody())
            .containsKey("requestId");
        assertThat(status(bearer)).containsEntry("pending", null);
        assertThat(refusal(withdraw(bearer), 422)).isEqualTo("ACCOUNT_DELETION_NONE");

        clock.advance(Duration.ofDays(15));
        assertThat(jobs.run(accountDeletionJob, clock.instant())).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        assertThat(query("SELECT erased_at FROM sec_user_version WHERE user_id = ?::uuid", userId))
            .extracting(row -> row.get("erased_at")).containsOnlyNulls();
        assertThat(query("SELECT e.outcome FROM sys_account_deletion_end e JOIN sys_account_deletion d"
            + " USING (request_id) WHERE d.user_id = ?::uuid", userId)).extracting(row -> row.get("outcome"))
            .containsExactly("CANCELLED");
    }

    @Test
    void aMailedLinkMakesTheRequestWithoutTheApplication() {
        String email = address("webber");
        String userId = customer(email);
        insert(CommerceEntities.CUSTOMER_DATASET, Map.of("customerCode", unique("C"), "customerName",
            "Webber " + userId, "userId", userId), null);
        String unverified = address("unproven");
        String other = customer(unverified);
        bypassingTheGuard("UPDATE sec_user_version SET verified_email = NULL WHERE user_id = '" + other + "'");

        // Alike for every address; only the user at a verified address gets a link.
        askByMail(PORTAL, address("nobody")).expectStatus().isAccepted();
        askByMail(PORTAL, unverified).expectStatus().isAccepted();
        askByMail(PORTAL, email).expectStatus().isAccepted();
        assertThat(refusal(askByMail(LOOSE, email), 422)).isEqualTo("ACCOUNT_DELETION_UNAVAILABLE");
        deliver();
        assertThat(to(unverified)).isEmpty();
        String token = token(LINK, email);

        // The notices to read come back, and the link still works.
        Map<String, Object> unread = confirm(token, List.of()).expectStatus().isEqualTo(422).expectBody(MAP)
            .returnResult().getResponseBody();
        assertThat(violations(unread)).extracting(v -> v.get("ruleCode"))
            .contains("erasure.notice.commerce.orders-kept");
        confirm(token, List.of("commerce.orders-kept")).expectStatus().isOk();
        assertThat(query("SELECT channel, requested_by FROM sys_account_deletion WHERE user_id = ?::uuid", userId)
            .getFirst()).containsEntry("channel", "WEB");
        assertThat(refusal(confirm(token, List.of("commerce.orders-kept")), 422)).isEqualTo("TOKEN_INVALID");
        assertThat(refusal(confirm("no-such-token", List.of()), 422)).isEqualTo("TOKEN_INVALID");
    }

    @Test
    void anAdministratorSchedulesAndWithdrawsButHoldersOfProtectedPermissionsAreRefused() {
        String email = address("scheduled");
        String userId = customer(email);
        Map<String, Object> scheduled = run(AccountDeletionProcesses.SCHEDULE, Map.of("userId", userId))
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        assertThat(scheduled).containsKey("output");
        assertThat(query("SELECT channel FROM sys_account_deletion WHERE user_id = ?::uuid", userId).getFirst())
            .containsEntry("channel", "ADMIN");
        run(AccountDeletionProcesses.UNSCHEDULE, Map.of("userId", userId)).expectStatus().isOk();
        assertThat(refusal(run(AccountDeletionProcesses.UNSCHEDULE, Map.of("userId", userId)), 422))
            .isEqualTo("ACCOUNT_DELETION_NONE");

        String admin = createUser(unique("admin"), PASSWORD);
        assign(admin, createRole(unique("ROLE"), "*"), null);
        assertThat(refusal(run(AccountDeletionProcesses.SCHEDULE, Map.of("userId", admin)), 422))
            .isEqualTo("ACCOUNT_DELETION_PROTECTED");
        // Nobody signed in runs the job's process.
        assertThat(refusal(run(AccountDeletionProcesses.EXECUTE, Map.of("requestId", userId)), 403)).isNotNull();
    }

    @Test
    void aLegalHoldKeepsTheRequestPendingUntilItIsReleased() {
        String email = address("held");
        String userId = customer(email);
        run(AccountDeletionProcesses.SCHEDULE, Map.of("userId", userId)).expectStatus().isOk();
        post("/api/processes/LEGAL_HOLD_PLACE/latest", bearer("legal.hold.write"), Map.of("name", unique("hold"),
            "reason", "court order", "entityType", "SecUser", "ids", List.of(userId))).expectStatus().isOk();

        clock.advance(Duration.ofDays(15));
        assertThat(jobs.run(accountDeletionJob, clock.instant())).isNotEqualTo(JobRunner.Outcome.SUCCEEDED);
        assertThat(query("SELECT erased_at FROM sec_user_version WHERE user_id = ?::uuid", userId))
            .extracting(row -> row.get("erased_at")).containsOnlyNulls();
        assertThat(query("SELECT * FROM sys_account_deletion_end e JOIN sys_account_deletion d USING (request_id)"
            + " WHERE d.user_id = ?::uuid", userId)).isEmpty();
    }

    @Test
    void elsewhereNobodyAsks() {
        String email = address("looser");
        customer(email);
        String bearer = bearerOf(loginTo(email, PASSWORD, LOOSE).expectStatus().isOk().expectBody(MAP)
            .returnResult().getResponseBody());
        assertThat(status(bearer)).containsEntry("available", false);
        assertThat(refusal(ask(bearer, PASSWORD, List.of()), 422)).isEqualTo("ACCOUNT_DELETION_UNAVAILABLE");
    }

    @Test
    void withoutAMaintenanceConnectionTheStartFailsAndBrokenLoadsAreReported() {
        org.springframework.beans.factory.support.StaticListableBeanFactory beans =
            new org.springframework.beans.factory.support.StaticListableBeanFactory();
        beans.addBean("broken", new com.jabiz.security.erasure.PersonalDataEraser() {
            @Override
            public List<com.jabiz.security.erasure.ErasedEntity> entities() {
                return List.of();
            }

            @Override
            public List<com.jabiz.security.erasure.ErasureLoad> loads() {
                return List.of(com.jabiz.security.erasure.ErasureLoad.of("a", "urn:no:such", "userId"),
                    com.jabiz.security.erasure.ErasureLoad.of("b", CommerceEntities.CUSTOMER_DATASET, "owner"));
            }
        });
        com.jabiz.runtime.erasure.PersonalDataErasers erasers = new com.jabiz.runtime.erasure.PersonalDataErasers(
            beans.getBeanProvider(com.jabiz.security.erasure.PersonalDataEraser.class));
        org.springframework.mock.env.MockEnvironment production = new org.springframework.mock.env.MockEnvironment();
        org.springframework.mock.env.MockEnvironment development = new org.springframework.mock.env.MockEnvironment();
        development.setActiveProfiles("dev");

        assertThat(new com.jabiz.runtime.erasure.AccountDeletionChecks(entries, erasers, datasets, definitions,
            production).check()).extracting(p -> p.severity() + " " + p.location() + ": " + p.message())
            .containsExactlyInAnyOrder(
                "ERROR jabiz.security.entries.portal: account-deletion is on, but no maintenance connection"
                    + " (jabiz.erasure.datasource.url, JABIZ_ERASURE_DATASOURCE_URL) is configured: personal data"
                    + " would never be purged (set it, or run with the dev profile)",
                "ERROR PersonalDataEraser " + erasers.all().getFirst().getClass().getName() + ": load a reads unknown"
                    + " dataset urn:no:such",
                "ERROR PersonalDataEraser " + erasers.all().getFirst().getClass().getName() + ": load b: entity"
                    + " Customer has no field owner");
        assertThat(new com.jabiz.runtime.erasure.AccountDeletionChecks(entries, erasers, datasets, definitions,
            development).check()).extracting(p -> p.severity().toString())
            .containsExactlyInAnyOrder("WARNING", "ERROR", "ERROR");
    }

    // ================= helpers =================

    /** A customer at a verified address, signing in with {@link #PASSWORD}; returns the user id. */
    @SuppressWarnings("unchecked")
    private String customer(String email) {
        Map<String, Object> output = (Map<String, Object>) post("/api/processes/SEC_USER_CREATE/latest", admin(),
            Map.of("userName", email, "displayName", "Customer", "password", PASSWORD, "email", email))
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody().get("output");
        String userId = (String) output.get("userId");
        // The address proven, as a mailed link would.
        bypassingTheGuard("UPDATE sec_user_version SET verified_email = email WHERE user_id = '" + userId + "'");
        assign(userId, roleId(CUSTOMER), null);
        return userId;
    }

    private Map<String, Object> portal(String email) {
        return loginTo(email, PASSWORD, PORTAL).expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody();
    }

    private Map<String, Object> status(String bearer) {
        return get("/api/auth/account/deletion", bearer).expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody();
    }

    private WebTestClient.ResponseSpec ask(String bearer, String password, List<String> acknowledged) {
        Map<String, Object> body = new HashMap<>();
        body.put("currentPassword", password);
        body.put("acknowledgedNotices", acknowledged);
        return post("/api/auth/account/deletion", bearer, body);
    }

    private WebTestClient.ResponseSpec withdraw(String bearer) {
        return post("/api/auth/account/deletion/cancel", bearer, Map.of());
    }

    private WebTestClient.ResponseSpec askByMail(String entry, String email) {
        return anonymous("/api/auth/account-deletion", Map.of("entry", entry, "email", email));
    }

    private WebTestClient.ResponseSpec confirm(String token, List<String> acknowledged) {
        return anonymous("/api/auth/account-deletion/confirm", Map.of("token", token, "acknowledgedNotices",
            acknowledged));
    }

    private WebTestClient.ResponseSpec run(String process, Map<String, Object> input) {
        return post("/api/processes/" + process + "/latest", bearer("security.user.erase"), input);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> notices(Map<String, Object> status) {
        return (List<Map<String, Object>>) status.get("notices");
    }

    private static void bypassingTheGuard(String statement) {
        try (java.sql.Connection connection = DB.connect(schema());
             java.sql.Statement sql = connection.createStatement()) {
            sql.execute("SET session_replication_role = replica");
            sql.execute(statement);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
