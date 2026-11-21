package com.jabiz.quizbuks.it;

import com.jabiz.context.RequestContext;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.firstlaunch.FirstLaunchExit;
import com.jabiz.runtime.firstlaunch.FirstLaunchGate;
import com.jabiz.runtime.firstlaunch.FirstLaunchRun;
import com.jabiz.runtime.firstlaunch.FirstLaunchRuns;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-LAUNCH Q3 (docs/quizbuks/plans/impl/F-LAUNCH.md section 9): the example configuration
 * ({@code first-launch/example/}) on a new database. The content phase makes the accounts, runs {@code QB_SETUP},
 * publishes the money switches at their first launch's values, the configured parameter and the approval rules,
 * makes the two operators with their leads as their own sponsors, imports each sponsor's package as that sponsor
 * (its images stored as uploaded by the sponsor), submits the pages and graphs, approves them, waits for them, then
 * the publications: the application opens with everything live, and no money moved. Then the waiting phase (item
 * 17): the job invites the accounts; the operators' leads accept, bind a second factor and get their roles; the super
 * administrator gets hers only once the operator confirmed the fingerprint; the run is done and its report mailed
 * with QuizBuks's items left for people; a taker passes a publication (offline redemption, no money); a start with
 * the configuration still there does nothing again. An operator's other member joins once her roles are in effect
 * (the platform's account-ready hook).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "jabiz.first-launch.await-interval=100ms", "jabiz.jobs.lock-at-least-for=0s",
    "jabiz.events.delivery.enabled=true", "jabiz.mail.enabled=true",
    "jabiz.mail.from=quizbuks@example.com", "jabiz.mail.base-url=https://app.quizbuks.test",
    "spring.mail.host=localhost", "spring.mail.port=3043"})
@Import(QbFirstLaunchIT.Exits.class)
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
class QbFirstLaunchIT extends PostgresIntegrationTest {

    @org.junit.jupiter.api.extension.RegisterExtension
    static final com.icegreen.greenmail.junit5.GreenMailExtension MAIL =
        new com.icegreen.greenmail.junit5.GreenMailExtension(InvitationIT.patient(
            new com.icegreen.greenmail.util.ServerSetup(3043, null, com.icegreen.greenmail.util.ServerSetup.PROTOCOL_SMTP)));

    /** What the operator confirmed (JABIZ_FIRST_LAUNCH_CONFIRM_ADMINS), read anew at every use. */
    static final java.util.concurrent.atomic.AtomicReference<String> CONFIRMED =
        new java.util.concurrent.atomic.AtomicReference<>("");

    static final List<Integer> EXITS = new CopyOnWriteArrayList<>();
    static Path directory;

    @TestConfiguration
    static class Exits {
        @Bean
        FirstLaunchExit qbFirstLaunchItExit() {
            return EXITS::add;
        }
    }

    @DynamicPropertySource
    static void firstLaunch(DynamicPropertyRegistry registry) {
        registry.add("jabiz.first-launch.config", () -> example("kansai-direct"));
        registry.add("jabiz.first-launch.confirm-admins", CONFIRMED::get);
    }

    /** The example written out, its packages made from the template with a real image. */
    static String example(String kansaiHandle) {
        try {
            directory = Files.createTempDirectory("qb-first-launch");
            Files.createDirectories(directory.resolve("sponsors/tokyo"));
            Files.createDirectories(directory.resolve("sponsors/kansai"));
            Files.writeString(directory.resolve("first-launch.yml"), resource("first-launch.yml"));
            byte[] jpeg = jpeg();
            String template = resource("sponsors/package.template.json")
                .replace("__JPEG__", Base64.getEncoder().encodeToString(jpeg))
                .replace("__SIZE__", String.valueOf(jpeg.length));
            Files.writeString(directory.resolve("sponsors/tokyo/package.json"), template
                .replace("__HANDLE__", "tokyo-direct").replace("__COMPANY__", "Tokyo Direct")
                .replace("__CONTACT__", "Misaki Tanaka"));
            Files.writeString(directory.resolve("sponsors/kansai/package.json"), template
                .replace("__HANDLE__", kansaiHandle).replace("__COMPANY__", "Kansai Direct")
                .replace("__CONTACT__", "Ken Yamada"));
            return directory.resolve("first-launch.yml").toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String resource(String path) throws IOException {
        try (InputStream in = QbFirstLaunchIT.class.getResourceAsStream("/first-launch/example/" + path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static byte[] jpeg() throws IOException {
        BufferedImage image = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(240, 160, 190));
        graphics.fillRect(0, 0, 64, 48);
        graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    @AfterAll
    static void cleanUp() throws IOException {
        if (directory != null) {
            try (Stream<Path> paths = Files.walk(directory)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    @Autowired
    FirstLaunchGate gate;

    @Autowired
    FirstLaunchRuns runs;

    @Autowired
    com.jabiz.runtime.process.ProcessExecutor executor;

    @Autowired
    com.jabiz.runtime.process.ProcessRegistry registry;

    private static final RequestContext SYSTEM = RequestContext.system(Locale.ENGLISH, "it");

    private FirstLaunchRun run() {
        return runs.current().contextWrite(view -> RequestContexts.put(view, SYSTEM)).block().orElseThrow();
    }

    private void awaitContent() {
        long end = System.nanoTime() + Duration.ofMinutes(5).toNanos();
        while (!gate.isOpen()) {
            if (!EXITS.isEmpty() || System.nanoTime() > end) {
                throw new AssertionError("The content phase did not finish (exits " + EXITS + "): "
                    + query("SELECT kind, step_key, reason_code, reason FROM sys_first_launch_event ORDER BY event_time"));
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    private static List<Map<String, Object>> latest(String table, String key, String where) {
        return query("SELECT * FROM (SELECT DISTINCT ON (" + key + ") * FROM " + table + " ORDER BY " + key
            + ", effect_start_time DESC, version_no DESC) v WHERE " + where);
    }

    private static String userOf(String email) {
        return String.valueOf(latest("sec_user_version", "user_id", "email = '" + email + "'").getFirst()
            .get("user_id"));
    }

    private static Object param(String key) {
        List<Map<String, Object>> rows = latest("sys_param_version", "param_key", "param_key = '" + key + "'");
        return rows.isEmpty() ? null : rows.getFirst().get("param_value");
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    void theExampleLaunchesEverythingLiveAndMovesNoMoney() {
        awaitContent();
        assertThat(run().state()).isEqualTo(FirstLaunchRun.State.CONTENT_DONE);
        assertThat(EXITS).isEmpty();

        // Two operators, the first the default, each with its lead as its own sponsor.
        List<Map<String, Object>> operators = query("SELECT * FROM qb_operator");
        assertThat(operators).extracting(row -> row.get("name"))
            .containsExactlyInAnyOrder("Tokyo Operations", "Kansai Operations");
        assertThat(operators).filteredOn(row -> Boolean.TRUE.equals(row.get("default_for_new")))
            .extracting(row -> row.get("name")).containsExactly("Tokyo Operations");
        String tokyo = userOf("b.tanaka@example.co.jp");
        String kansai = userOf("c.yamada@example.co.jp");
        assertThat(operators).extracting(row -> String.valueOf(row.get("direct_sponsor_id")))
            .containsExactlyInAnyOrder(tokyo, kansai);

        // The pages are published, the graphs in effect and the publications live, all the sponsors' own.
        assertThat(query("SELECT * FROM qb_sponsor_page")).extracting(row -> row.get("status"))
            .containsOnly("PUBLISHED").hasSize(2);
        assertThat(query("SELECT * FROM qb_tree_version WHERE status = 'ACTIVE'")).hasSize(2);
        List<Map<String, Object>> publications = latest("qb_publication_version", "publication_id", "true");
        assertThat(publications).extracting(row -> row.get("status")).containsOnly("LIVE").hasSize(2);
        assertThat(publications).extracting(row -> row.get("reward_method")).containsOnly("OFFLINE_REDEEM");
        assertThat(publications).extracting(row -> String.valueOf(row.get("sponsor_id")))
            .containsExactlyInAnyOrder(tokyo, kansai);

        // The images are stored as uploaded by the sponsors (avatar, cover and the question's image each).
        assertThat(query("SELECT uploaded_by FROM sys_file")).extracting(row -> String.valueOf(row.get("uploaded_by")))
            .hasSize(6).containsOnly(tokyo, kansai);

        // The configured parameter and a money switch took effect; a switch that waits for a decision did not.
        assertThat(String.valueOf(param("qb.ai.sponsor.daily-jobs"))).contains("5");
        assertThat(String.valueOf(param("qb.kudos.buy.enabled"))).contains("false");
        assertThat(param("qb.ai.enabled")).isNull();

        // Every approval the run asked for was decided by the first launch, level by level, bound to its content.
        List<Map<String, Object>> requests = latest("sys_approval_request_version", "request_id", "true");
        assertThat(requests).isNotEmpty().allSatisfy(request -> {
            assertThat(request.get("status")).isEqualTo("APPROVED");
            assertThat(String.valueOf(request.get("content_hash"))).hasSize(64);
        });
        List<Map<String, Object>> decisions = query("SELECT request_id, approver_id FROM sys_approval_decision");
        assertThat(decisions).hasSizeGreaterThanOrEqualTo(requests.size())
            .allSatisfy(row -> assertThat(String.valueOf(row.get("approver_id"))).startsWith("first-launch:"));
        assertThat(decisions).extracting(row -> String.valueOf(row.get("request_id"))).containsAll(
            requests.stream().map(row -> String.valueOf(row.get("request_id"))).toList());

        // The approval rules were published; the separation of duties stays a proposal for people (D82).
        List<Map<String, Object>> changes = latest("sys_control_change_version", "change_id", "true");
        assertThat(changes).filteredOn(row -> "SysApprovalRule".equals(row.get("target_entity")))
            .hasSize(com.jabiz.quizbuks.setup.QbApprovalRules.ALL.size())
            .allSatisfy(row -> assertThat(row.get("status")).isEqualTo("PUBLISHED"));
        assertThat(changes).filteredOn(row -> "SysSodRule".equals(row.get("target_entity")))
            .hasSize(com.jabiz.quizbuks.setup.QbSodRules.ALL.size())
            .allSatisfy(row -> assertThat(row.get("status")).isEqualTo("PROPOSED"));
        // Publications are reviewed: the switch is in effect at its declared value (no automatic approval).
        assertThat(String.valueOf(param("qb.review.publication.auto"))).contains("false");

        // Every consumer of the approvals decided takes them in its stride, though the transfers' period has no value
        // yet: the transfers' consumer recognises that none of them is a transfer's before it needs a setting.
        assertThat(param("qb.transfer.hold-days")).isNull();
        assertThat(query("SELECT 1 FROM sys_outbox_event WHERE event_type = 'jabiz.approval.approved'")).isNotEmpty();
        while (deliverer.deliverPending().block() > 0) {
            // until every event is delivered
        }
        assertThat(query("SELECT consumer, error FROM sys_outbox_attempt")).isEmpty();
        assertThat(query("SELECT 1 FROM sys_event_consumption c JOIN sys_outbox_event e ON e.event_id = c.event_id"
            + " WHERE e.event_type = 'jabiz.approval.approved' AND c.consumer = 'qb.transfer-approved'")).isNotEmpty();

        // Nothing moved money or was held for it (plan section 4).
        assertThat(query("SELECT 1 FROM ledger_transaction_version")).isEmpty();
        assertThat(query("SELECT 1 FROM ledger_entry_version")).isEmpty();
        assertThat(query("SELECT 1 FROM qb_prepayment")).isEmpty();
        assertThat(query("SELECT 1 FROM qb_wallet WHERE balance <> 0 OR pending_amount <> 0 OR pending_out <> 0"))
            .isEmpty();

        // Once the content phase is done, no system call makes an operator with a lead who has not verified.
        String unverified = userOf("a.suzuki@example.co.jp");
        @SuppressWarnings("unchecked")
        var save = (com.jabiz.process.ProcessDefinition<Object, Object, com.jabiz.process.ProcessContext>)
            registry.findLatest(com.jabiz.quizbuks.operator.OperatorProcesses.SAVE).orElseThrow();
        long operatorsBefore = query("SELECT 1 FROM qb_operator").size();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> executor.execute(save,
                new com.jabiz.quizbuks.operator.OperatorProcesses.SaveInput(null, "Late", "A", "a@example.co.jp",
                    null, null, null, "ja", unverified, null))
                .contextWrite(view -> RequestContexts.put(view, SYSTEM)).block())
            .isInstanceOf(com.jabiz.runtime.PermissionDeniedException.class);
        assertThat(query("SELECT 1 FROM qb_operator")).hasSize((int) operatorsBefore);
    }

    // ---- the waiting phase (plan section 9, item 17) ----------------------------------------------------------------

    static final String A = "a.suzuki@example.co.jp";
    static final String B = "b.tanaka@example.co.jp";
    static final String C = "c.yamada@example.co.jp";
    static final String OWN = "own horse battery staple";
    static final java.util.regex.Pattern INVITE = java.util.regex.Pattern.compile(
        "auth/invitation\\?entry=admin&token=([A-Za-z0-9_-]+)");

    @Autowired
    org.springframework.context.ApplicationContext context;

    @Autowired
    com.jabiz.runtime.job.JobRunner jobs;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("firstLaunchJob")
    com.jabiz.job.JobDefinition<com.jabiz.runtime.firstlaunch.accounts.FirstLaunchWaiting.AdvanceInput> job;

    @Autowired
    com.jabiz.runtime.security.JwtService tokens;

    @Autowired
    com.jabiz.runtime.event.OutboxDeliverer deliverer;

    private static int round;

    private org.springframework.test.web.reactive.server.WebTestClient client() {
        return org.springframework.test.web.reactive.server.WebTestClient.bindToApplicationContext(context)
            .configureClient().responseTimeout(Duration.ofSeconds(60))
            .defaultHeader(org.springframework.http.HttpHeaders.ACCEPT_LANGUAGE, "en").build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String path, String token, Map<String, ?> body, int status) {
        var request = client().post().uri(path).contentType(org.springframework.http.MediaType.APPLICATION_JSON);
        if (token != null) {
            request = request.header(org.springframework.http.HttpHeaders.AUTHORIZATION, token);
        }
        return request.bodyValue(body).exchange().expectStatus().isEqualTo(status).expectBody(Map.class)
            .returnResult().getResponseBody();
    }

    /** One run of the job at a scheduled time of its own, then a moment for the mail it queued to go out. */
    private void advance() {
        round++;
        java.time.Instant scheduled = clock.instant().plus(Duration.ofMinutes(1))
            .truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        clock.set(scheduled.plusSeconds(1));
        assertThat(jobs.run(job, scheduled)).isEqualTo(com.jabiz.runtime.job.JobRunner.Outcome.SUCCEEDED);
    }

    private List<jakarta.mail.internet.MimeMessage> to(String address) {
        return java.util.Arrays.stream(MAIL.getReceivedMessages()).filter(message -> {
            try {
                return message.getAllRecipients()[0].toString().equalsIgnoreCase(address);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).toList();
    }

    /** Waits for the event delivery to send the mail to {@code address}. */
    private List<jakarta.mail.internet.MimeMessage> awaitMail(String address, int count) throws InterruptedException {
        long end = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (to(address).size() < count && System.nanoTime() < end) {
            Thread.sleep(100);
        }
        assertThat(to(address)).as("mail to " + address).hasSizeGreaterThanOrEqualTo(count);
        return to(address);
    }

    /** A mail's plain text, decoded. */
    private static String text(jakarta.mail.internet.MimeMessage message) {
        try {
            String found = plain(message);
            return found == null ? "" : found;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String plain(jakarta.mail.Part part) throws Exception {
        if (part.isMimeType("text/plain")) {
            return String.valueOf(part.getContent());
        }
        if (part.getContent() instanceof jakarta.mail.Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                String found = plain(multipart.getBodyPart(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** The token of the newest invitation to the address, waiting for it to be sent. */
    private String invitation(String address) throws InterruptedException {
        long end = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        do {
            for (jakarta.mail.internet.MimeMessage message : to(address).reversed()) {
                java.util.regex.Matcher matcher = INVITE.matcher(text(message));
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
            Thread.sleep(100);
        } while (System.nanoTime() < end);
        throw new AssertionError("No invitation to " + address);
    }

    /** Accepts the invitation with a password of one's own, signs in and binds a second factor. */
    private void acceptAndBind(String address) throws InterruptedException {
        client().post().uri("/api/auth/invitation/confirm").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("token", invitation(address), "password", OWN)).exchange().expectStatus().isNoContent();
        Map<String, Object> first = post("/api/auth/login", null, Map.of("userName", address, "password", OWN,
            "entry", "admin"), 200);
        assertThat(first).containsEntry("status", "MFA_ENROLLMENT_REQUIRED");
        String challenge = (String) first.get("challenge");
        String secret = (String) post("/api/auth/challenge/enroll", null, Map.of("challenge", challenge), 200)
            .get("secret");
        post("/api/auth/challenge/enroll/confirm", null, Map.of("challenge", challenge, "code",
            com.jabiz.security.Totp.code(com.jabiz.security.Base32.decode(secret),
                com.jabiz.security.Totp.step(clock.instant()))), 200);
    }

    private static List<String> rolesOf(String userId) {
        return query("""
            SELECT r.role_code FROM (SELECT DISTINCT ON (user_role_id) role_id, is_deleted FROM sec_user_role_version
                WHERE user_id = ?::uuid ORDER BY user_role_id, version_no DESC) v
            JOIN (SELECT DISTINCT ON (role_id) role_id, role_code FROM sec_role_version
                ORDER BY role_id, version_no DESC) r ON r.role_id = v.role_id WHERE NOT v.is_deleted""", userId)
            .stream().map(row -> String.valueOf(row.get("role_code"))).toList();
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    void theAccountsGetTheirRolesThenTheRunIsDoneAndATakerAnswers() throws Exception {
        awaitContent();
        String pending = com.jabiz.quizbuks.setup.FirstLaunchRoles.PENDING;
        assertThat(rolesOf(userOf(B))).containsExactly(pending);
        // The job invites every account once the content is in place.
        advance();
        invitation(A);
        invitation(B);
        invitation(C);

        // B and C accept and bind a second factor: their roles are given, then the waiting role goes.
        acceptAndBind(B);
        acceptAndBind(C);
        advance();
        assertThat(rolesOf(userOf(B))).contains("QB_OPERATOR");
        advance();
        assertThat(rolesOf(userOf(B))).containsExactly("QB_OPERATOR");
        assertThat(rolesOf(userOf(C))).containsExactly("QB_OPERATOR");

        // A, the super administrator, only once the operator has confirmed the fingerprint.
        acceptAndBind(A);
        advance();
        assertThat(rolesOf(userOf(A))).doesNotContain("QB_ADMIN_SUPER");
        assertThat(run().state()).isEqualTo(FirstLaunchRun.State.CONTENT_DONE);
        java.time.Instant bound = ((java.sql.Timestamp) query("SELECT confirmed_time FROM sec_user_mfa_version"
            + " WHERE user_id = ?::uuid AND confirmed ORDER BY version_no DESC LIMIT 1", userOf(A)).getFirst()
            .get("confirmed_time")).toInstant();
        CONFIRMED.set(com.jabiz.runtime.firstlaunch.accounts.GrantPlan.fingerprint(run().runId().toString(),
            java.util.UUID.fromString(userOf(A)), bound));
        advance();
        assertThat(rolesOf(userOf(A))).contains("QB_ADMIN_SUPER");
        advance();
        // A, once her roles are in effect, joins the operator that names her among its members (account-ready hook).
        String tokyo = String.valueOf(query("SELECT operator_id FROM qb_operator WHERE direct_sponsor_id = ?",
            userOf(B)).getFirst().get("operator_id"));
        assertThat(query("SELECT 1 FROM qb_operator_member WHERE operator_id = ? AND user_id = ?::uuid", tokyo, userOf(A)))
            .hasSize(1);
        assertThat(query("SELECT 1 FROM qb_operator_member WHERE user_id = ?::uuid", userOf(C))).hasSize(1);

        // Done: the report goes to A, with what is left for people (QuizBuks's items among them).
        assertThat(run().state()).isEqualTo(FirstLaunchRun.State.DONE);
        String report = text(awaitMail(A, 2).getLast());
        assertThat(report).contains("QB_LEGAL_PUBLISH").contains("qb.sod.*").contains("QB_ADMIN_SUPER");
        // Still no money moved.
        assertThat(query("SELECT 1 FROM ledger_transaction_version")).isEmpty();

        // A taker finds a publication and passes it (offline redemption: no Kudos move).
        String taker = String.valueOf(post("/api/processes/SEC_USER_CREATE/latest",
            com.jabiz.runtime.test.TestTokens.bearer(tokens, "it-admin", "*"), Map.of("userName", "fl-taker",
                "displayName", "Taker", "email", "taker@example.com"), 200).get("output") instanceof Map<?, ?> out
            ? out.get("userId") : null);
        String takerToken = com.jabiz.runtime.test.TestTokens.bearer(tokens, taker,
            com.jabiz.quizbuks.setup.QbRoles.of(com.jabiz.quizbuks.setup.QbRoles.TAKER).permissions()
                .toArray(String[]::new));
        String publicationId = String.valueOf(latest("qb_publication_version", "publication_id",
            "sponsor_id = '" + userOf(B) + "'").getFirst().get("publication_id"));
        @SuppressWarnings("unchecked")
        Map<String, Object> started = (Map<String, Object>) post("/api/processes/QB_ATTEMPT_START/latest", takerToken,
            Map.of("publicationId", publicationId), 200).get("output");
        String attemptId = (String) started.get("attemptId");
        // Answered as the App answers: by the options as this attempt shows them (their order is the attempt's).
        Map<String, String> right = Map.of("When do cherries blossom in Tokyo?", "Late March",
            "Which flower is this?", "Cherry", "Is a tulip a spring flower?", "Yes");
        List<Object> answers = new java.util.ArrayList<>();
        for (Object shown : (List<?>) started.get("questions")) {
            Map<?, ?> question = (Map<?, ?>) shown;
            Object option = ((List<?>) question.get("options")).stream().map(Map.class::cast)
                .filter(o -> right.get(String.valueOf(question.get("stem"))).equals(o.get("text"))).findFirst()
                .orElseThrow().get("no");
            answers.add(Map.of("questionNo", question.get("no"), "selected", List.of(option)));
        }
        Map<String, Object> submission = new java.util.LinkedHashMap<>(Map.of("attemptId", attemptId,
            "publicationId", publicationId, "answers", answers));
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) post("/api/processes/QB_ATTEMPT_SUBMIT/latest", takerToken,
            submission, 200).get("output");
        assertThat(result).containsEntry("passed", true);
        assertThat(query("SELECT 1 FROM ledger_transaction_version")).isEmpty();

        // Started again with the configuration still there: nothing is done again (a warning only).
        long events = query("SELECT 1 FROM sys_first_launch_event").size();
        FirstLaunchGate another = new FirstLaunchGate();
        new com.jabiz.runtime.firstlaunch.FirstLaunchRunner(context.getEnvironment(), runs, another,
            context.getBean(com.jabiz.runtime.process.ProcessExecutor.class),
            context.getBeanProvider(com.jabiz.firstlaunch.FirstLaunchHandler.class),
            context.getBeanProvider(com.jabiz.runtime.firstlaunch.FirstLaunchPlatformSteps.class),
            context.getBean(com.jabiz.i18n.MessageCatalog.class), context.getBean(java.time.Clock.class),
            context.getBeanProvider(FirstLaunchExit.class),
            context.getBean(tools.jackson.databind.json.JsonMapper.class),
            context.getBean(com.jabiz.runtime.firstlaunch.FirstLaunchPowers.class),
            context.getBean(com.jabiz.runtime.firstlaunch.FirstLaunchFiles.class)).afterSingletonsInstantiated();
        assertThat(another.isOpen()).isTrue();
        assertThat(query("SELECT 1 FROM sys_first_launch_event")).hasSize((int) events);
        assertThat(EXITS).isEmpty();
    }
}
