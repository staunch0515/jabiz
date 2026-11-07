package com.jabiz.app.it.mail;

import com.jabiz.app.commerce.CommerceEntities;
import com.jabiz.app.commerce.OrderConfirmations;
import com.jabiz.app.it.fixture.ItMailFixtures;
import com.jabiz.app.it.fixture.ItTaskFixtures;
import com.jabiz.app.it.fixture.SqlStatementLog;
import com.jabiz.app.it.security.SecurityItSupport;
import com.jabiz.runtime.approval.ApprovalPermissions;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import com.jabiz.runtime.event.OutboxDeliverer;
import com.jabiz.runtime.mail.MailLayoutEntities;
import com.jabiz.runtime.mail.MailPermissions;
import com.jabiz.runtime.security.JwtService;
import com.jabiz.runtime.task.MailMessage;
import com.jabiz.runtime.task.NotificationSender;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The mail layout (decision D72; ROADMAP phase 17zh-b): every mail is sent on the default layout of its language
 * (configuration) and the delivery records which; a layout of its own only through a controlled change another person
 * publishes, and back to the default at once; task notifications and documents are sent on the layout recorded with
 * them; preview and test mail; only the platform's test mail is sent as rendered (17zh-a review, F6b); the startup
 * check; the layouts are only appended. Each test keeps to a language
 * of its own, as a layout is per language.
 */
@SpringBootTest(properties = {"jabiz.mail.enabled=true", "jabiz.mail.from=jabiz@example.com",
    "jabiz.mail.base-url=https://app.example.com", "spring.mail.host=localhost", "spring.mail.port=3031",
    "it.sql-log.enabled=true"})
class MailLayoutIT extends SecurityItSupport {

    /** Records what would be sent. */
    @TestConfiguration
    static class Recorder {

        static final List<MailMessage> SENT = new CopyOnWriteArrayList<>();

        @Bean
        @Primary
        NotificationSender recordingSender() {
            return new NotificationSender() {
                @Override
                public void send(String to, String subject, String body) {
                    SENT.add(new MailMessage(to, subject, body, List.of()));
                }

                @Override
                public void send(MailMessage message) {
                    SENT.add(message);
                }
            };
        }
    }

    private static final String NEWS = "news";

    @Autowired
    OutboxDeliverer deliverer;

    @Autowired
    JwtService jwt;

    @Autowired
    ApplicationContext beans;

    @BeforeEach
    void forget() {
        Recorder.SENT.clear();
    }

    private String proposer() {
        return TestTokens.bearer(jwt, "it-layout-proposer", ApprovalPermissions.CONTROL_PROPOSE,
            ApprovalPermissions.CONTROL_PUBLISH, MailPermissions.TEXT_WRITE, MailPermissions.TEXT_READ);
    }

    private String publisher() {
        return TestTokens.bearer(jwt, "it-layout-publisher", ApprovalPermissions.CONTROL_PUBLISH,
            MailPermissions.TEXT_WRITE);
    }

    private Map<String, Object> run(String process, String authorization, Map<String, Object> input, int status) {
        var exchange = post("/api/processes/" + process + "/latest", authorization, input).expectBody(MAP)
            .returnResult();
        assertThat(exchange.getStatus().value()).as("%s: %s", process, exchange.getResponseBody()).isEqualTo(status);
        return exchange.getResponseBody();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> output(Map<String, Object> response) {
        return (Map<String, Object>) response.get("output");
    }

    private static List<String> codes(Map<String, Object> problem) {
        return violations(problem).stream().map(violation -> (String) violation.get("ruleCode")).toList();
    }

    private static Map<String, Object> proposal(String locale, String displayName, String logoUrl, String footer) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("locale", locale);
        values.put("displayName", displayName);
        values.put("logoUrl", logoUrl);
        values.put("logoAlt", logoUrl.isEmpty() ? "" : displayName);
        values.put("footer", footer);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("targetEntity", MailLayoutEntities.ENTITY);
        input.put("values", values);
        input.put("reason", "new letterhead");
        return input;
    }

    /** Proposes and has another person publish a layout; the change's id. */
    private String publish(String locale, String displayName, String footer) {
        String changeId = (String) output(run("CONTROL_CHANGE_PROPOSE", proposer(), proposal(locale, displayName,
            "{baseUrl}/brand/logo.png", footer), 200)).get("changeId");
        assertThat(output(run("CONTROL_CHANGE_PUBLISH", publisher(), Map.of("changeId", changeId), 200)))
            .containsEntry("status", "PUBLISHED");
        return changeId;
    }

    private Map<String, Object> reset(String locale, Integer base, int status) {
        Map<String, Object> input = new HashMap<>();
        input.put("locale", locale);
        input.put("baseVersionNo", base);
        return run("MAIL_LAYOUT_RESET", TestTokens.bearer(jwt, "it-layout-writer", MailPermissions.TEXT_WRITE),
            input, status);
    }

    @SuppressWarnings("unchecked")
    private String user(String address, String locale) {
        Map<String, Object> input = new HashMap<>();
        input.put("userName", unique("layout"));
        input.put("email", address);
        input.put("locale", locale);
        Map<String, Object> result = post("/api/processes/SEC_USER_CREATE/latest", admin(), input)
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        return (String) ((Map<String, Object>) result.get("output")).get("userId");
    }

    private static String address(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }

    /** Queues a mail through the test process, without delivering it. */
    private void queue(String template, String userId, String value) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("template", template);
        input.put("userId", userId);
        input.put("value", value);
        input.put("fail", false);
        post("/api/processes/IT_MAIL_SEND/latest", bearer(ItMailFixtures.PERMISSION), input).expectStatus().isOk();
    }

    private MailMessage sendNews(String locale) {
        String address = address("news-" + locale);
        queue(NEWS, user(address, locale), "crates");
        deliverer.deliverPending().block();
        assertThat(sentTo(address)).hasSize(1);
        return sentTo(address).getFirst();
    }

    private static List<MailMessage> sentTo(String address) {
        return Recorder.SENT.stream().filter(message -> message.to().equals(address)).toList();
    }

    /** The layout version the attempts of the mail to {@code address} recorded. */
    private static List<Object> layoutVersions(String address) {
        return query("SELECT a.layout_version_no FROM sys_mail_attempt a JOIN sys_mail_message m"
            + " ON m.message_id = a.message_id WHERE m.address = ? ORDER BY a.attempted_time", address).stream()
            .map(row -> row.get("layout_version_no")).toList();
    }

    @Test
    void everyMailIsSentOnTheDefaultLayoutOfItsLanguage() {
        MailMessage mail = sendNews("zh");
        // The demo application's default layout (jabiz.mail.layout.zh.*).
        assertThat(mail.htmlBody()).startsWith("<!DOCTYPE html>").contains("Jabiz 示范",
            "href=\"https://app.example.com/\"", "max-width:600px").doesNotContain("<script", "<style");
        assertThat(mail.body()).startsWith("Jabiz 示范\n\n").contains("\n-- \nJabiz 示范应用");
        assertThat(layoutVersions(mail.to())).containsExactly((Object) null);
    }

    @Test
    void aLayoutOfItsOwnOnlyThroughAControlledChangeAnotherPersonPublishes() {
        SqlStatementLog.clear();
        // The controlled change's own permissions are not enough, nor is an unverified address or no second factor.
        post("/api/processes/CONTROL_CHANGE_PROPOSE/latest", TestTokens.bearer(jwt, "it-layout-outsider",
            ApprovalPermissions.CONTROL_PROPOSE), proposal("ja", "Shop", "", "")).expectStatus().isForbidden();
        post("/api/processes/CONTROL_CHANGE_PROPOSE/latest", TestTokens.unverified(jwt, "it-layout-unverified",
            ApprovalPermissions.CONTROL_PROPOSE, MailPermissions.TEXT_WRITE), proposal("ja", "Shop", "", ""))
            .expectStatus().value(status -> assertThat(status).isBetween(400, 499));
        post("/api/processes/CONTROL_CHANGE_PROPOSE/latest", TestTokens.withoutMfa(jwt, "it-layout-proposer",
            ApprovalPermissions.CONTROL_PROPOSE, MailPermissions.TEXT_WRITE), proposal("ja", "Shop", "", ""))
            .expectStatus().isForbidden();
        // The rules, every problem at once.
        assertThat(codes(run("CONTROL_CHANGE_PROPOSE", proposer(), proposal("ja", "Shop",
            "https://tracker.example.net/p.gif", "[x](https://evil.example) [s]({unsubscribeUrl})"), 422)))
            .containsExactlyInAnyOrder("MAIL_LAYOUT_LOGO", "MAIL_TEXT_LINK_HOST", "MAIL_TEXT_UNKNOWN_PLACEHOLDER");

        String changeId = (String) output(run("CONTROL_CHANGE_PROPOSE", proposer(), proposal("ja", "ショップ",
            "{baseUrl}/brand/logo.png", "株式会社ショップ · [ヘルプ]({baseUrl}/help)"), 200)).get("changeId");
        assertThat(query("SELECT target_key FROM sys_control_change_version WHERE change_id::text = ? LIMIT 1",
            changeId).getFirst().get("target_key")).isEqualTo("ja");
        assertThat(ruleCode(run("CONTROL_CHANGE_PUBLISH", proposer(), Map.of("changeId", changeId), 422)))
            .isEqualTo("CONTROL_SAME_PERSON");
        assertThat(query("SELECT count(*) AS n FROM sys_mail_layout_version WHERE locale = 'ja'").getFirst().get("n"))
            .isEqualTo(0L);
        assertThat(output(run("CONTROL_CHANGE_PUBLISH", publisher(), Map.of("changeId", changeId), 200)))
            .containsEntry("status", "PUBLISHED");

        MailMessage mail = sendNews("ja");
        assertThat(mail.htmlBody()).contains("ショップ", "src=\"https://app.example.com/brand/logo.png\"",
            "href=\"https://app.example.com/help\"").doesNotContain("Jabiz デモ");
        assertThat(layoutVersions(mail.to())).containsExactly(1);
        Map<String, Object> list = get("/api/mail/layouts", bearer(MailPermissions.TEXT_READ)).expectStatus().isOk()
            .expectBody(MAP).returnResult().getResponseBody();
        assertThat(language(list, "ja")).containsEntry("state", "CUSTOM").containsEntry("versionNo", 1);
        assertThat(language(list, "zh")).containsEntry("state", "DEFAULT");

        // A change proposed against version 1 cannot be published once the layout is another version.
        String stale = (String) output(run("CONTROL_CHANGE_PROPOSE", proposer(), proposal("ja", "ショップ2", "",
            ""), 200)).get("changeId");
        // Back to the default at once, without approval; a stale base is a conflict.
        reset("ja", null, 409);
        assertThat(output(reset("ja", 1, 200))).containsEntry("kind", "DEFAULT").containsEntry("versionNo", 2);
        assertThat(ruleCode(run("CONTROL_CHANGE_PUBLISH", publisher(), Map.of("changeId", stale), 422)))
            .isEqualTo("CONTROL_TARGET_CHANGED");
        MailMessage back = sendNews("ja");
        assertThat(back.htmlBody()).contains("Jabiz デモ").doesNotContain("ショップ");
        assertThat(layoutVersions(back.to())).containsExactly((Object) null);

        // No other way writes a layout: the dataset API is refused.
        long stored = count("sys_mail_layout_version");
        post("/api/datasets/" + MailLayoutEntities.DATASET + "/commit", admin(), Map.of("changes", List.of(Map.of(
            "action", "INSERT", "attributes", Map.of("locale", "en", "kind", "CUSTOM", "displayName", "x",
                "defaultHash", "x"))))).expectStatus().value(status -> assertThat(status).isBetween(400, 499));
        assertThat(count("sys_mail_layout_version")).isEqualTo(stored);
        // Nor does the reset write a layout of its own for a language that has none.
        assertThat(output(reset("zh", null, 200))).containsEntry("kind", "DEFAULT").containsEntry("versionNo", null);

        // The layouts are only appended.
        assertThat(SqlStatementLog.STATEMENTS).isNotEmpty().noneMatch(sql -> sql.matches(
            "(?is).*(UPDATE|DELETE FROM)\\s+sys_mail_layout_version\\b.*"));
        assertThat(query("SELECT version_no, layout_kind FROM sys_mail_layout_version WHERE locale = 'ja'"
            + " ORDER BY version_no")).extracting(row -> row.get("version_no") + ":" + row.get("layout_kind"))
            .containsExactly("1:CUSTOM", "2:DEFAULT");
        assertThatThrownBy(() -> execute("UPDATE sys_mail_layout_version SET display_name = 'x' WHERE locale = 'ja'"))
            .hasMessageContaining("append-only");
        assertThatThrownBy(() -> execute("DELETE FROM sys_mail_layout_version WHERE locale = 'ja'"))
            .hasMessageContaining("append-only");
    }

    @Test
    void notificationsAndDocumentsAreSentOnTheLayoutRecordedWithThem() throws InterruptedException {
        publish("en", "Example Shop", "Example Shop Ltd · [Help]({baseUrl}/help)");
        String address = address("assignee");
        String assignee = user(address, "en");
        String key = unique("task");
        Map<String, Object> input = new HashMap<>(Map.of("key", key, "item", "the crates"));
        input.put("user", assignee);
        post("/api/processes/IT_TASK_OPEN/latest", bearer(ItTaskFixtures.PERMISSION), input).expectStatus().isOk();
        deliverer.deliverPending().block();
        assertThat(query("SELECT n.layout_version_no FROM sys_notification n JOIN sys_task_version t"
            + " ON t.task_id = n.task_id WHERE t.source_key = ? AND t.version_no = 1", key))
            .extracting(row -> row.get("layout_version_no")).containsExactly(1);
        MailMessage notification = awaitSentTo(address).getFirst();
        assertThat(notification.htmlBody()).contains("Example Shop", "href=\"https://app.example.com/help\"");
        assertThat(notification.body()).startsWith("Example Shop\n\n");

        String code = "L" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
        insert(CommerceEntities.WAREHOUSE_DATASET, Map.of("warehouseCode", code, "warehouseName", "North " + code,
            "active", true), null);
        insert(CommerceEntities.PRODUCT_DATASET, Map.of("sku", code, "productName", "Apple crate", "unitPrice", 1200,
            "active", true), null);
        run("STOCK_RECEIVE", admin(), Map.of("warehouseCode", code, "sku", code, "quantity", 10), 200);
        clock.advance(Duration.ofMinutes(1));
        String orderId = (String) output(run("ORDER_PLACE", admin(), Map.of("orderNo", code + "-1", "customerCode",
            code, "warehouseCode", code, "lines", List.of(Map.of("sku", code, "quantity", 3))), 200)).get("orderId");
        clock.advance(Duration.ofMinutes(1));
        Map<String, Object> sent = output(run(OrderConfirmations.SEND, TestTokens.bearer(jwt, "it-layout-clerk",
            "commerce.order.confirm", "commerce.order.read"), Map.of("orderId", orderId), 200));
        String contact = code.toLowerCase(Locale.ROOT) + "@customers.example.com";
        MailMessage document = awaitSentTo(contact).getFirst();
        assertThat(document.attachments()).hasSize(1);
        assertThat(query("SELECT layout_version_no FROM sys_document_delivery WHERE run_id = ?::uuid",
            sent.get("runId"))).extracting(row -> row.get("layout_version_no")).containsExactly(1);
        assertThat(document.htmlBody()).startsWith("<!DOCTYPE html>").contains("Example Shop");
        assertThat(document.body()).startsWith("Example Shop\n\n").contains("\n-- \nExample Shop Ltd");
    }

    private static List<MailMessage> awaitSentTo(String address) throws InterruptedException {
        for (int i = 0; i < 100 && sentTo(address).isEmpty(); i++) {
            Thread.sleep(100);
        }
        return sentTo(address);
    }

    @Test
    void thePreviewWritesNothingAndTheTestMailGoesToItsOperatorOnly() {
        long operations = count("op_process");
        long layouts = count("sys_mail_layout_version");
        Map<String, Object> preview = post("/api/mail/layouts/preview", bearer(MailPermissions.TEXT_READ),
            Map.of("locale", "zh", "layout", Map.of("displayName", "草稿店", "logoUrl", "https://t.example/p.gif",
                "logoAlt", "x", "footer", "草稿页脚"))).expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody();
        assertThat((String) preview.get("html")).contains("草稿店", "草稿页脚").doesNotContain("t.example");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> problems = (List<Map<String, Object>>) preview.get("problems");
        assertThat(problems).extracting(problem -> problem.get("ruleCode")).containsExactly("MAIL_LAYOUT_LOGO");
        Map<String, Object> current = post("/api/mail/layouts/preview", bearer(MailPermissions.TEXT_READ),
            Map.of("locale", "zh")).expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        assertThat((String) current.get("html")).contains("Jabiz 示范");
        // The texts' preview is on the layout in effect too.
        assertThat((String) post("/api/mail/texts/preview", bearer(MailPermissions.TEXT_READ),
            Map.of("template", "commerce.order-shipped", "locale", "zh")).expectStatus().isOk().expectBody(MAP)
            .returnResult().getResponseBody().get("html")).contains("Jabiz 示范");
        post("/api/mail/layouts/preview", bearer("mail.read"), Map.of("locale", "zh")).expectStatus().isForbidden();
        get("/api/mail/layouts", bearer("mail.read")).expectStatus().isForbidden();
        assertThat(count("op_process")).isEqualTo(operations);
        assertThat(count("sys_mail_layout_version")).isEqualTo(layouts);

        String address = address("layout-operator");
        String operator = user(address, "zh");
        post("/api/processes/IT_EMAIL_VERIFY/latest", admin(), Map.of("userId", operator)).expectStatus().isOk();
        String token = TestTokens.bearer(jwt, operator, MailPermissions.TEXT_WRITE);
        Map<String, Object> sent = output(run("MAIL_LAYOUT_TEST_SEND", token, Map.of("locale", "zh",
            "displayName", "草稿店", "logoUrl", "", "logoAlt", "", "footer", "草稿页脚"), 200));
        assertThat(sent).containsEntry("address", address);
        deliverer.deliverPending().block();
        MailMessage mail = sentTo(address).getFirst();
        assertThat(mail.subject()).startsWith("[TEST] ");
        assertThat(mail.htmlBody()).contains("草稿店", "草稿页脚");
        // A draft the rules refuse is not sent; nothing was stored.
        run("MAIL_LAYOUT_TEST_SEND", token, Map.of("locale", "zh", "displayName", "x",
            "logoUrl", "https://t.example/p.gif"), 400);
        assertThat(count("sys_mail_layout_version")).isEqualTo(layouts);
        // Without a draft: the layout in effect.
        run("MAIL_LAYOUT_TEST_SEND", token, Map.of("locale", "zh"), 200);
        deliverer.deliverPending().block();
        assertThat(sentTo(address)).last().satisfies(last -> assertThat(last.htmlBody()).contains("Jabiz 示范"));
    }

    @Test
    void onlyThePlatformsTestMailIsSentAsRendered() {
        String address = address("forged");
        queue(NEWS, user(address, "zh"), "crates");
        // As if a process of the application had recorded a message with rendered parts of its own (F6b).
        String forged = "forged-" + UUID.randomUUID().toString().substring(0, 8);
        execute("INSERT INTO sys_mail_message (message_id, template, category, user_id, address, locale, params,"
            + " created_time, process_seq_id, version, rendered_subject, rendered_text, rendered_html)"
            + " SELECT ?, template, category, user_id, address, locale, params, created_time, process_seq_id, version,"
            + " 'Raw', 'raw', '<form action=\"https://evil.example\">' FROM sys_mail_message WHERE address = ?",
            forged, address);
        execute("INSERT INTO sys_outbox_event (event_id, event_type, entity_type, entity_id, payload, process_seq_id,"
            + " created_time) SELECT gen_random_uuid(), e.event_type, e.entity_type, e.entity_id,"
            + " jsonb_set(e.payload, '{messageId}', to_jsonb(?::text)), e.process_seq_id, e.created_time"
            + " FROM sys_outbox_event e JOIN sys_mail_message m ON m.message_id = e.payload->>'messageId'"
            + " WHERE m.address = ? AND m.rendered_subject IS NULL", forged, address);
        deliverer.deliverPending().block();
        assertThat(sentTo(address)).hasSize(1).allSatisfy(mail -> assertThat(mail.subject()).isNotEqualTo("Raw"));
        assertThat(query("SELECT outcome, detail FROM sys_mail_attempt WHERE message_id = ?", forged))
            .isNotEmpty().allSatisfy(row -> {
                assertThat(row.get("outcome")).isEqualTo("FAILED");
                assertThat((String) row.get("detail")).contains("only a test mail");
            });
    }

    @Test
    void aStoredLayoutOfALanguageTheApplicationNoLongerHasIsWarnedAbout() {
        // As if the application had dropped a language since; its defaults pass the checks.
        execute("INSERT INTO entity_registry (entity_id, entity_type, created_seq_id)"
            + " SELECT '0193f000-0000-7000-8000-00000000ab01', 'MailLayout', max(process_seq_id) FROM op_process");
        execute("INSERT INTO sys_mail_layout_version (mail_layout_id, version_no, effect_start_time, created_time,"
            + " process_seq_id, locale, layout_kind, display_name, logo_url, logo_alt, footer, default_hash)"
            + " SELECT '0193f000-0000-7000-8000-00000000ab01', 1, now(), now(), max(process_seq_id), 'xx', 'CUSTOM',"
            + " 'Old', '', '', '[x](https://gone.example)', 'x' FROM op_process");
        List<CheckProblem> warnings = beans.getBeansOfType(PlatformCheck.class).values().stream()
            .flatMap(check -> check.check().stream())
            .filter(problem -> problem.location().startsWith("mail layout [xx]")).toList();
        assertThat(warnings).singleElement().satisfies(problem -> {
            assertThat(problem.severity()).isEqualTo(CheckProblem.Severity.WARNING);
            assertThat(problem.message()).contains("language");
        });
        assertThat(beans.getBeansOfType(PlatformCheck.class).values().stream()
            .flatMap(check -> check.check().stream())
            .filter(problem -> problem.location().startsWith("jabiz.mail.layout"))).isEmpty();
    }

    private static long count(String table) {
        return (Long) query("SELECT count(*) AS n FROM " + table).getFirst().get("n");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> language(Map<String, Object> list, String locale) {
        return ((List<Map<String, Object>>) list.get("languages")).stream()
            .filter(language -> locale.equals(language.get("locale"))).findFirst().orElseThrow();
    }
}
