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
import com.jabiz.runtime.mail.MailPermissions;
import com.jabiz.runtime.mail.MailTextEntities;
import com.jabiz.runtime.mail.MailTexts;
import com.jabiz.runtime.mail.PlatformMailTemplates;
import com.jabiz.runtime.security.AuthMailTemplates;
import com.jabiz.runtime.security.JwtService;
import com.jabiz.runtime.task.MailMessage;
import com.jabiz.runtime.task.NotificationSender;
import com.jabiz.runtime.test.TestTokens;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
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
 * Mail texts administrators change (decision D72; ROADMAP phase 17zh-a): an ordinary template's texts are in effect
 * as soon as saved and the delivery records the version it used; returning to the default; conflicting writes; the
 * texts of mail with a token or a protected text change only by a controlled change another person publishes; an
 * override the rules no longer accept falls back to the default with a warning; the test mail goes to its operator
 * only, with no token stored, and is rate limited; the preview writes nothing; task notifications and documents take
 * the same texts; the texts are only appended.
 */
@SpringBootTest(properties = {"jabiz.mail.enabled=true", "jabiz.mail.from=jabiz@example.com",
    "jabiz.mail.base-url=https://app.example.com", "spring.mail.host=localhost", "spring.mail.port=3031",
    "it.sql-log.enabled=true"})
class MailTextIT extends SecurityItSupport {

    /** Records what would be sent: no mail server is needed to see the texts. */
    @TestConfiguration
    static class Recorder {

        static final List<MailMessage> SENT = new CopyOnWriteArrayList<>();
        /** The observations of an override not used, as {@code template=<name>}. */
        static final List<String> FALLBACKS = new CopyOnWriteArrayList<>();

        @Bean
        ObservationHandler<Observation.Context> fallbackRecorder() {
            return new ObservationHandler<>() {
                @Override
                public boolean supportsContext(Observation.Context context) {
                    return MailTexts.FALLBACK.equals(context.getName());
                }

                @Override
                public void onStart(Observation.Context context) {
                    context.getLowCardinalityKeyValues().forEach(tag -> FALLBACKS.add(tag.getKey() + "="
                        + tag.getValue()));
                }
            };
        }

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

    private static final String NEWS_BODY = "New about **{item}**.\n\n[Stop these]({unsubscribeUrl})";

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

    private String writer() {
        return TestTokens.bearer(jwt, "it-text-writer", MailPermissions.TEXT_WRITE, MailPermissions.TEXT_READ);
    }

    private Map<String, Object> save(String template, String locale, String subject, String body, Integer base,
        int status) {
        Map<String, Object> input = new HashMap<>();
        input.put("template", template);
        input.put("locale", locale);
        input.put("subject", subject);
        input.put("body", body);
        input.put("baseVersionNo", base);
        return run("MAIL_TEXT_SAVE", writer(), input, status);
    }

    private Map<String, Object> reset(String template, String locale, Integer base, int status) {
        Map<String, Object> input = new HashMap<>();
        input.put("template", template);
        input.put("locale", locale);
        input.put("baseVersionNo", base);
        return run("MAIL_TEXT_RESET", writer(), input, status);
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

    @SuppressWarnings("unchecked")
    private String user(String address, String locale) {
        Map<String, Object> input = new HashMap<>();
        input.put("userName", unique("text"));
        input.put("email", address);
        input.put("locale", locale);
        Map<String, Object> result = post("/api/processes/SEC_USER_CREATE/latest", admin(), input)
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        return (String) ((Map<String, Object>) result.get("output")).get("userId");
    }

    private static String address(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }

    private void send(String template, String userId, String value) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("template", template);
        input.put("userId", userId);
        input.put("value", value);
        input.put("fail", false);
        post("/api/processes/IT_MAIL_SEND/latest", bearer(ItMailFixtures.PERMISSION), input).expectStatus().isOk();
        deliverer.deliverPending().block();
    }

    private static List<MailMessage> sentTo(String address) {
        return Recorder.SENT.stream().filter(message -> message.to().equals(address)).toList();
    }

    /** The text version the attempts of the mail to {@code address} recorded. */
    private static List<Object> textVersions(String address) {
        return query("SELECT a.text_version_no FROM sys_mail_attempt a JOIN sys_mail_message m"
            + " ON m.message_id = a.message_id WHERE m.address = ? ORDER BY a.attempted_time", address).stream()
            .map(row -> row.get("text_version_no")).toList();
    }

    @Test
    void anOrdinaryTemplatesTextsAreInEffectOnceSavedAndTheDeliveryRecordsTheirVersion() {
        Map<String, Object> saved = output(save("it.news", "en", "Fresh: {item}", NEWS_BODY, null, 200));
        assertThat(saved).containsEntry("kind", "CUSTOM").containsEntry("versionNo", 1);

        String address = address("fresh");
        send("news", user(address, "en"), "plums");
        MailMessage mail = sentTo(address).getFirst();
        assertThat(mail.subject()).isEqualTo("Fresh: plums");
        assertThat(mail.body()).contains("New about plums.").contains("Stop these");
        assertThat(mail.htmlBody()).contains("<strong>plums</strong>").contains("/mail/unsubscribe?token=");
        assertThat(textVersions(address)).containsExactly(1);

        // The next version is based on the first; the delivery then uses it.
        assertThat(output(save("it.news", "en", "Fresher: {item}", NEWS_BODY, 1, 200))).containsEntry("versionNo", 2);
        String other = address("fresher");
        send("news", user(other, "en"), "pears");
        assertThat(sentTo(other).getFirst().subject()).isEqualTo("Fresher: pears");
        assertThat(textVersions(other)).containsExactly(2);
        assertThat(query("SELECT count(*) AS n FROM sys_audit_record WHERE entity_type = ?", MailTextEntities.ENTITY)
            .getFirst().get("n")).isNotEqualTo(0L);
    }

    @Test
    void returningToTheDefaultIsInEffectAtOnce() {
        save("it.news", "ja", "速報 {item}", NEWS_BODY, null, 200);
        assertThat(output(reset("it.news", "ja", 1, 200))).containsEntry("kind", "DEFAULT")
            .containsEntry("versionNo", 2);
        String address = address("reset");
        send("news", user(address, "ja"), "梨");
        assertThat(sentTo(address).getFirst().subject()).isEqualTo("梨 のお知らせ");
        assertThat(textVersions(address)).containsExactly((Object) null);
        // Nothing to return from: nothing written.
        assertThat(output(reset("it.news", "ja", 2, 200))).containsEntry("versionNo", 2);
        // A text saved as the default is a return to it.
        save("it.news", "ja", "速報 {item}", NEWS_BODY, 2, 200);
        assertThat(output(save("it.news", "ja", "{item} のお知らせ",
            "{item} についてお知らせがあります。\n\n[配信停止]({unsubscribeUrl})", 3, 200)))
            .containsEntry("kind", "DEFAULT").containsEntry("versionNo", 4);
    }

    @Test
    void aChangeBasedOnAnOlderVersionIsAConflict() {
        save("it.news", "zh", "快讯 {item}", NEWS_BODY, null, 200);
        save("it.news", "zh", "又一条 {item}", NEWS_BODY, null, 409);
        save("it.news", "zh", "又一条 {item}", NEWS_BODY, 7, 409);
        reset("it.news", "zh", null, 409);
        assertThat(query("SELECT count(*) AS n FROM sys_mail_text_version WHERE template = 'it.news'"
            + " AND locale = 'zh'").getFirst().get("n")).isEqualTo(1L);
    }

    @Test
    void textsTheRulesRefuseAreNotSavedAndEveryProblemIsReported() {
        Map<String, Object> refused = save("it.offers", "ja", "Deal\n{stray}",
            "<b>x</b> ![p](https://tracker.example.net/p.gif) [x](https://tracker.example.net/c)", null, 400);
        assertThat(codes(refused)).contains("MAIL_TEXT_SUBJECT_LINE", "MAIL_TEXT_UNKNOWN_PLACEHOLDER",
            "MAIL_TEXT_REQUIRED_PLACEHOLDER", "MAIL_TEXT_HTML", "MAIL_TEXT_IMAGE", "MAIL_TEXT_LINK_HOST");
        assertThat(save("it.offers", "ja", "x".repeat(301) + "{item}", NEWS_BODY, null, 400))
            .satisfies(problem -> assertThat(codes(problem)).containsExactly("MAIL_TEXT_TOO_LONG"));
        assertThat(save("nope.nothing", "ja", "s", "b", null, 400)).satisfies(problem ->
            assertThat(codes(problem)).containsExactly("INVALID_VALUE"));
        assertThat(save("it.offers", "fr", "s {item}", NEWS_BODY, null, 400)).satisfies(problem ->
            assertThat(codes(problem)).containsExactly("INVALID_VALUE"));
        // Without the permission, or without a verified address, nothing is saved either.
        post("/api/processes/MAIL_TEXT_SAVE/latest", bearer(MailPermissions.TEXT_READ), Map.of("template", "it.offers",
            "locale", "ja", "subject", "s {item}", "body", NEWS_BODY)).expectStatus().isForbidden();
        post("/api/processes/MAIL_TEXT_SAVE/latest", TestTokens.unverified(jwt, "it-unverified",
            MailPermissions.TEXT_WRITE), Map.of("template", "it.offers", "locale", "ja", "subject", "s {item}",
            "body", NEWS_BODY)).expectStatus().isForbidden();
        assertThat(query("SELECT count(*) AS n FROM sys_mail_text_version WHERE template = 'it.offers'"
            + " AND locale = 'ja'").getFirst().get("n")).isEqualTo(0L);
    }

    @Test
    void securityTextsChangeOnlyThroughAControlledChangeAnotherPersonPublishes() {
        String verifyBody = "Dear {userName},\n\n[Confirm now]({baseUrl}/verify?token={verify})";
        assertThat(ruleCode(save("it.verify", "en", "Confirm {userName}", verifyBody, null, 422)))
            .isEqualTo("MAIL_TEXT_NEEDS_APPROVAL");
        // A security notice without a token is protected all the same.
        assertThat(ruleCode(save(AuthMailTemplates.PASSWORD_CHANGED, "en", "Password changed",
            "[Reset]({entryUrl}auth/password-reset?entry={entry})", null, 422))).isEqualTo("MAIL_TEXT_NEEDS_APPROVAL");

        String proposer = TestTokens.bearer(jwt, "it-text-proposer", ApprovalPermissions.CONTROL_PROPOSE,
            ApprovalPermissions.CONTROL_PUBLISH, MailPermissions.TEXT_WRITE);
        String publisher = TestTokens.bearer(jwt, "it-text-publisher", ApprovalPermissions.CONTROL_PUBLISH,
            MailPermissions.TEXT_WRITE);
        // The controlled change's own permissions are not enough: proposing and publishing a mail text need what
        // writing one needs, a verified address too (decision D72 item 1).
        post("/api/processes/CONTROL_CHANGE_PROPOSE/latest", TestTokens.bearer(jwt, "it-text-outsider",
            ApprovalPermissions.CONTROL_PROPOSE), proposal("it.verify", "Confirm {userName}", verifyBody))
            .expectStatus().isForbidden();
        post("/api/processes/CONTROL_CHANGE_PROPOSE/latest", TestTokens.unverified(jwt, "it-text-unverified",
            ApprovalPermissions.CONTROL_PROPOSE, MailPermissions.TEXT_WRITE), proposal("it.verify",
            "Confirm {userName}", verifyBody)).expectStatus().value(status -> assertThat(status).isBetween(400, 499));
        // Proposing needs a recent second factor, as publishing does.
        post("/api/processes/CONTROL_CHANGE_PROPOSE/latest", TestTokens.withoutMfa(jwt, "it-text-proposer",
            ApprovalPermissions.CONTROL_PROPOSE), proposal("it.verify", "Confirm {userName}", verifyBody))
            .expectStatus().isForbidden();
        // The same rules: a token in the text is refused when proposed.
        assertThat(codes(run("CONTROL_CHANGE_PROPOSE", proposer, proposal("it.verify", "Confirm {userName}",
            verifyBody + " or paste {verify}"), 422))).contains("MAIL_TEXT_SECRET_PLACEMENT");
        // Ordinary templates take MAIL_TEXT_SAVE.
        assertThat(codes(run("CONTROL_CHANGE_PROPOSE", proposer, proposal("it.news", "News {item}", NEWS_BODY), 422)))
            .containsExactly("CONTROL_CHANGE_INVALID");

        String changeId = (String) output(run("CONTROL_CHANGE_PROPOSE", proposer, proposal("it.verify",
            "Confirm {userName}", verifyBody), 200)).get("changeId");
        assertThat(ruleCode(run("CONTROL_CHANGE_PUBLISH", proposer, Map.of("changeId", changeId), 422)))
            .isEqualTo("CONTROL_SAME_PERSON");
        post("/api/processes/CONTROL_CHANGE_PUBLISH/latest", TestTokens.bearer(jwt, "it-text-other-publisher",
            ApprovalPermissions.CONTROL_PUBLISH), Map.of("changeId", changeId)).expectStatus().isForbidden();
        assertThat(query("SELECT count(*) AS n FROM sys_mail_text_version WHERE template = 'it.verify'")
            .getFirst().get("n")).isEqualTo(0L);
        // The list of changes names the mail and language (17zn).
        assertThat(query("SELECT target_key FROM sys_control_change_version WHERE change_id::text = ? LIMIT 1",
            changeId).getFirst().get("target_key")).isEqualTo("it.verify [en]");
        assertThat(output(run("CONTROL_CHANGE_PUBLISH", publisher, Map.of("changeId", changeId), 200)))
            .containsEntry("status", "PUBLISHED");

        String address = address("verify");
        send("verify", user(address, "en"), "Ann");
        MailMessage mail = sentTo(address).getFirst();
        assertThat(mail.subject()).isEqualTo("Confirm Ann");
        assertThat(mail.body()).contains("Dear Ann").containsPattern("/verify\\?token=[A-Za-z0-9_-]{20,}");
        assertThat(textVersions(address)).containsExactly(1);

        // A change proposed against version 1 cannot be published once the text is another version.
        String stale = (String) output(run("CONTROL_CHANGE_PROPOSE", proposer, proposal("it.verify",
            "Please confirm {userName}", verifyBody), 200)).get("changeId");
        assertThat(output(reset("it.verify", "en", 1, 200))).containsEntry("kind", "DEFAULT");
        assertThat(ruleCode(run("CONTROL_CHANGE_PUBLISH", publisher, Map.of("changeId", stale), 422)))
            .isEqualTo("CONTROL_TARGET_CHANGED");

        // No other way writes a text: the dataset API is refused, and so is any write without the processes' grant.
        post("/api/datasets/" + MailTextEntities.DATASET + "/commit", admin(), Map.of("changes", List.of(Map.of(
            "action", "INSERT", "attributes", Map.of("template", "it.reset", "locale", "en", "kind", "CUSTOM",
                "subject", "x", "body", "[r]({baseUrl}?t={reset})", "defaultHash", "x"))))).expectStatus()
            .value(status -> assertThat(status).isBetween(400, 499));
        assertThat(query("SELECT count(*) AS n FROM sys_mail_text_version WHERE template = 'it.reset'"
            + " AND locale = 'en'").getFirst().get("n")).isEqualTo(0L);
    }

    private static Map<String, Object> proposal(String template, String subject, String body) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("targetEntity", MailTextEntities.ENTITY);
        input.put("values", Map.of("template", template, "locale", "en", "subject", subject, "body", body));
        input.put("reason", "clearer wording");
        return input;
    }

    @Test
    void anOverrideTheRulesNoLongerAcceptFallsBackToTheDefaultWithAWarning() {
        save("it.offers", "en", "Deal: {item}", "Deal on {item}. [Stop]({unsubscribeUrl})", null, 200);
        // As if the code had dropped a parameter since: the stored text uses one the template no longer has.
        execute("INSERT INTO sys_mail_text_version (mail_text_id, version_no, effect_start_time, created_time,"
            + " process_seq_id, is_deleted, template, locale, text_kind, subject, body, default_hash)"
            + " SELECT mail_text_id, 2, effect_start_time, created_time, process_seq_id, false, template, locale,"
            + " 'CUSTOM', 'Deal: {item} {gone}', body, default_hash FROM sys_mail_text_version"
            + " WHERE template = 'it.offers' AND locale = 'en' AND version_no = 1");

        String address = address("offers");
        send("offers", user(address, "en"), "figs");
        assertThat(sentTo(address).getFirst().subject()).isEqualTo("Offers for figs");
        assertThat(textVersions(address)).containsExactly((Object) null);
        // Observed by the template's name alone.
        assertThat(Recorder.FALLBACKS).contains("template=it.offers").allMatch(tag -> tag.startsWith("template="));

        List<CheckProblem> warnings = beans.getBeansOfType(PlatformCheck.class).values().stream()
            .filter(check -> check.getClass().getSimpleName().startsWith("MailTextChecks"))
            .flatMap(check -> check.check().stream()).toList();
        assertThat(warnings).anySatisfy(problem -> {
            assertThat(problem.location()).isEqualTo("mail text it.offers [en]");
            assertThat(problem.message()).contains("no longer passes the rules", "{gone}");
            assertThat(problem.severity()).isEqualTo(CheckProblem.Severity.WARNING);
        });
        assertThat(language(template(texts(writer()), "it.offers"), "en")).containsEntry("state", "INVALID")
            .containsEntry("versionNo", 2);
    }

    @Test
    void anOverrideSavedBeforeItsTemplateNeededApprovalIsNotSent() {
        // As if the text had been saved by MAIL_TEXT_SAVE when the template had no token yet (17zh-a review, F5):
        // a version written by a process other than a controlled change's publication, of a template that needs
        // approval now.
        execute("INSERT INTO entity_registry (entity_id, entity_type, created_seq_id)"
            + " SELECT '0193f000-0000-7000-8000-00000000f501', 'MailText', max(process_seq_id) FROM op_process");
        execute("INSERT INTO sys_mail_text_version (mail_text_id, version_no, effect_start_time, created_time,"
            + " process_seq_id, template, locale, text_kind, subject, body, default_hash)"
            + " SELECT '0193f000-0000-7000-8000-00000000f501', 1, now(), now(), max(process_seq_id), 'it.reset', 'zh',"
            + " 'CUSTOM', 'Unapproved', '[Go]({baseUrl}/r?t={reset})', 'x' FROM op_process"
            + " WHERE process_name <> 'CONTROL_CHANGE_PUBLISH'");
        Recorder.FALLBACKS.clear();
        String address = address("unapproved");
        send("reset", user(address, "zh"), "x");
        assertThat(sentTo(address)).extracting(MailMessage::subject).containsExactly("重置密码");
        assertThat(textVersions(address)).containsExactly((Object) null);
        assertThat(Recorder.FALLBACKS).contains("template=it.reset");
        assertThat(language(template(texts(bearer(MailPermissions.TEXT_READ)), "it.reset"), "zh"))
            .containsEntry("state", "UNAPPROVED");
        assertThat(beans.getBeansOfType(PlatformCheck.class).values().stream()
            .flatMap(check -> check.check().stream())
            .filter(problem -> problem.location().equals("mail text it.reset [zh]")))
            .singleElement().satisfies(problem -> assertThat(problem.message()).contains("without the approval"));
    }

    @Test
    void theTestMailGoesToItsOperatorOnlyWithoutATokenAndIsLimited() {
        // An operator whose address is not verified has none to send to (the token's claim is not enough).
        String unproven = user(address("unproven"), "en");
        assertThat(ruleCode(run("MAIL_TEXT_TEST_SEND", TestTokens.bearer(jwt, unproven, MailPermissions.TEXT_WRITE),
            Map.of("template", "it.news", "locale", "en"), 422))).isEqualTo("MAIL_NO_ADDRESS");

        String address = address("operator");
        String operator = user(address, "en");
        post("/api/processes/IT_EMAIL_VERIFY/latest", admin(), Map.of("userId", operator)).expectStatus().isOk();
        String token = TestTokens.bearer(jwt, operator, MailPermissions.TEXT_WRITE);
        Map<String, Object> draft = new HashMap<>(Map.of("template", "it.verify", "locale", "en",
            "subject", "Check {userName}", "body", "[Go]({baseUrl}/verify?token={verify}) {userName}",
            // Not an input: the address is always the operator's own.
            "address", "someone-else@example.com"));
        Map<String, Object> sent = output(run("MAIL_TEXT_TEST_SEND", token, draft, 200));
        assertThat(sent).containsEntry("address", address);
        deliverer.deliverPending().block();
        MailMessage mail = sentTo(address).getFirst();
        assertThat(mail.subject()).isEqualTo("[TEST] Check [userName]");
        assertThat(mail.body()).contains(MailTexts.SAMPLE_TOKEN);
        assertThat(sentTo("someone-else@example.com")).isEmpty();
        assertThat(query("SELECT count(*) AS n FROM sys_mail_token WHERE message_id = ?",
            sent.get("messageId")).getFirst().get("n")).isEqualTo(0L);
        assertThat(query("SELECT outcome FROM sys_mail_attempt WHERE message_id = ?", sent.get("messageId")))
            .extracting(row -> row.get("outcome")).containsExactly("SENT");

        // Without a draft: the texts in effect (here the default), with the template's samples.
        run("MAIL_TEXT_TEST_SEND", token, Map.of("template", "commerce.order-shipped", "locale", "en"), 200);
        deliverer.deliverPending().block();
        assertThat(sentTo(address)).last().satisfies(last -> assertThat(last.subject())
            .isEqualTo("[TEST] Your order SO-2026-0001 has shipped"));
        // A draft the rules refuse is not sent.
        run("MAIL_TEXT_TEST_SEND", token, Map.of("template", "it.verify", "locale", "en", "subject", "x {verify}",
            "body", "{userName}"), 400);
        // An operator who is no user has no address of their own.
        assertThat(ruleCode(run("MAIL_TEXT_TEST_SEND", writer(), Map.of("template", "it.news", "locale", "en"), 422)))
            .isEqualTo("MAIL_NO_ADDRESS");

        // Ten an hour per person by default: three so far.
        for (int i = 0; i < 7; i++) {
            run("MAIL_TEXT_TEST_SEND", token, Map.of("template", "it.news", "locale", "en"), 200);
        }
        assertThat(ruleCode(run("MAIL_TEXT_TEST_SEND", token, Map.of("template", "it.news", "locale", "en"), 429)))
            .isEqualTo("RATE_LIMITED");
    }

    @Test
    void thePreviewRendersWithSamplesAndWritesNothing() {
        long operations = count("op_process");
        long messages = count("sys_mail_message");
        long texts = count("sys_mail_text_version");
        Map<String, Object> preview = post("/api/mail/texts/preview", bearer(MailPermissions.TEXT_READ),
            Map.of("template", "it.verify", "locale", "en", "subject", "Hi {userName}",
                "body", "[Go]({baseUrl}/verify?token={verify}) code {verify}"))
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        assertThat(preview.get("subject")).isEqualTo("Hi [userName]");
        assertThat((String) preview.get("html")).contains("https://app.example.com/verify?token="
            + MailTexts.SAMPLE_TOKEN);
        assertThat(violations(preview)).isNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> problems = (List<Map<String, Object>>) preview.get("problems");
        assertThat(problems).extracting(problem -> problem.get("ruleCode"))
            .containsExactly("MAIL_TEXT_SECRET_PLACEMENT");
        // Without a draft: the texts in effect.
        Map<String, Object> current = post("/api/mail/texts/preview", bearer(MailPermissions.TEXT_READ),
            Map.of("template", "commerce.order-shipped", "locale", "en")).expectStatus().isOk().expectBody(MAP)
            .returnResult().getResponseBody();
        assertThat(current.get("subject")).isEqualTo("Your order SO-2026-0001 has shipped");
        post("/api/mail/texts/preview", bearer("mail.read"), Map.of("template", "it.verify", "locale", "en"))
            .expectStatus().isForbidden();
        assertThat(count("op_process")).isEqualTo(operations);
        assertThat(count("sys_mail_message")).isEqualTo(messages);
        assertThat(count("sys_mail_text_version")).isEqualTo(texts);
    }

    @Test
    void theListShowsWhatEachTemplateTakesAndWhetherChangesNeedApproval() {
        Map<String, Object> list = texts(bearer(MailPermissions.TEXT_READ));
        assertThat(list).containsEntry("maxSubjectLength", 300).containsEntry("maxBodyLength", 20000);
        assertThat(template(list, "it.verify")).containsEntry("needsApproval", true)
            .containsEntry("secrets", List.of("verify"));
        assertThat(template(list, AuthMailTemplates.PASSWORD_CHANGED)).containsEntry("needsApproval", true);
        assertThat(template(list, "commerce.order-shipped")).containsEntry("needsApproval", false)
            .containsEntry("required", List.of("orderNo", "totalAmount", "unsubscribeUrl"));
        assertThat(template(list, PlatformMailTemplates.TASK_NOTIFICATION)).containsEntry("needsApproval", false);
        assertThat(language(template(list, "commerce.order-shipped"), "en")).containsEntry("state", "DEFAULT")
            .containsEntry("defaultSubject", "Your order {orderNo} has shipped");
        get("/api/mail/texts", bearer("mail.read")).expectStatus().isForbidden();
    }

    @Test
    void taskNotificationsAndDocumentsTakeTheSameTexts() throws InterruptedException {
        // Task notifications are in the platform's default language.
        save(PlatformMailTemplates.TASK_NOTIFICATION, "en", "To do: {title}", "Please see to {title}.\n\nOpen: {taskUrl}",
            null, 200);
        String address = address("assignee");
        String assignee = user(address, "en");
        String key = unique("task");
        Map<String, Object> input = new HashMap<>(Map.of("key", key, "item", "the crates"));
        input.put("user", assignee);
        post("/api/processes/IT_TASK_OPEN/latest", bearer(ItTaskFixtures.PERMISSION), input).expectStatus().isOk();
        deliverer.deliverPending().block();
        Map<String, Object> notification = query("SELECT n.subject, n.body, n.text_version_no FROM sys_notification n"
            + " JOIN sys_task_version t ON t.task_id = n.task_id WHERE t.source_key = ? AND t.version_no = 1", key)
            .getFirst();
        assertThat((String) notification.get("subject")).isEqualTo("To do: Check the crates");
        assertThat((String) notification.get("body")).contains("Please see to Check the crates.",
            "Open: https://app.example.com/data");
        assertThat(notification.get("text_version_no")).isEqualTo(1);
        assertThat(awaitSentTo(address)).extracting(MailMessage::subject).containsExactly("To do: Check the crates");

        // Documents: an override of the template's texts applies to every layout.
        for (String locale : List.of("en", "ja", "zh")) {
            save(PlatformMailTemplates.DOCUMENT_MAIL, locale, "Your {title} {number}",
                "Attached: {title} {number}.\n\nRegards, {company}", null, 200);
        }
        String code = "T" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
        insert(CommerceEntities.WAREHOUSE_DATASET, Map.of("warehouseCode", code, "warehouseName", "North " + code,
            "active", true), null);
        insert(CommerceEntities.PRODUCT_DATASET, Map.of("sku", code, "productName", "Apple crate", "unitPrice", 1200,
            "active", true), null);
        run("STOCK_RECEIVE", admin(), Map.of("warehouseCode", code, "sku", code, "quantity", 10), 200);
        clock.advance(Duration.ofMinutes(1));
        String orderId = (String) output(run("ORDER_PLACE", admin(), Map.of("orderNo", code + "-1", "customerCode",
            code, "warehouseCode", code, "lines", List.of(Map.of("sku", code, "quantity", 3))), 200)).get("orderId");
        clock.advance(Duration.ofMinutes(1));
        Map<String, Object> sent = output(run(OrderConfirmations.SEND, TestTokens.bearer(jwt, "it-clerk",
            "commerce.order.confirm", "commerce.order.read"), Map.of("orderId", orderId), 200));
        String contact = code.toLowerCase(Locale.ROOT) + "@customers.example.com";
        MailMessage document = awaitSentTo(contact).getFirst();
        assertThat(document.subject()).startsWith("Your ").endsWith(code + "-1");
        assertThat(document.body()).contains("Attached: ", "Regards, ");
        assertThat(document.attachments()).hasSize(1);
        assertThat(query("SELECT text_version_no FROM sys_document_delivery WHERE run_id = ?::uuid",
            sent.get("runId"))).extracting(row -> row.get("text_version_no")).containsExactly(1);
    }

    private static List<MailMessage> awaitSentTo(String address) throws InterruptedException {
        for (int i = 0; i < 100 && sentTo(address).isEmpty(); i++) {
            Thread.sleep(100);
        }
        return sentTo(address);
    }

    @Test
    void theTextsAreOnlyAppended() {
        SqlStatementLog.clear();
        save("it.prospect", "en", "Hello {item}", "About {item}. [Stop]({unsubscribeUrl})", null, 200);
        save("it.prospect", "en", "Hi {item}", "About {item}. [Stop]({unsubscribeUrl})", 1, 200);
        reset("it.prospect", "en", 2, 200);
        assertThat(SqlStatementLog.STATEMENTS).isNotEmpty().noneMatch(sql -> sql.matches(
            "(?is).*(UPDATE|DELETE FROM)\\s+sys_mail_text_version\\b.*"));
        assertThat(query("SELECT version_no, text_kind FROM sys_mail_text_version WHERE template = 'it.prospect'"
            + " ORDER BY version_no")).extracting(row -> row.get("version_no") + ":" + row.get("text_kind"))
            .containsExactly("1:CUSTOM", "2:CUSTOM", "3:DEFAULT");
        assertThatThrownBy(() -> execute("UPDATE sys_mail_text_version SET subject = 'x' WHERE template = ?",
            "it.prospect")).hasMessageContaining("append-only");
        assertThatThrownBy(() -> execute("DELETE FROM sys_mail_text_version WHERE template = ?", "it.prospect"))
            .hasMessageContaining("append-only");
    }

    private static long count(String table) {
        return (Long) query("SELECT count(*) AS n FROM " + table).getFirst().get("n");
    }

    private Map<String, Object> texts(String authorization) {
        return get("/api/mail/texts", authorization).expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> template(Map<String, Object> list, String name) {
        return ((List<Map<String, Object>>) list.get("templates")).stream()
            .filter(template -> name.equals(template.get("template"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> language(Map<String, Object> template, String locale) {
        return ((List<Map<String, Object>>) template.get("languages")).stream()
            .filter(language -> locale.equals(language.get("locale"))).findFirst().orElseThrow();
    }
}
