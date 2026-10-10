package com.jabiz.app.it.mail;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;
import com.jabiz.app.it.fixture.ItMailFixtures;
import com.jabiz.app.it.fixture.SqlStatementLog;
import com.jabiz.app.it.security.SecurityItSupport;
import com.jabiz.runtime.event.OutboxDeliverer;
import com.jabiz.runtime.security.JwtService;
import com.jabiz.runtime.security.secret.SingleUseSecrets;
import com.jabiz.runtime.task.MailMessage;
import com.jabiz.runtime.task.NotificationSender;
import com.jabiz.runtime.task.SmtpNotificationSender;
import com.jabiz.runtime.test.TestTokens;
import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Template mail (docs/design/18-numbering-approvals-tasks.md section 5.6; decision D35; ROADMAP phase 16a) against a
 * local SMTP server: a process queues it in its transaction and the outbox sends it once, in the recipient's
 * language, as plain text and HTML; a rolled back process sends nothing; a failed attempt is recorded and retried;
 * one-time tokens are stored as hashes only and are good once, until they expire or a later mail supersedes them;
 * notifications can be turned off through the signed link, transactional mail cannot; nothing is updated or deleted.
 */
@SpringBootTest(properties = {"jabiz.mail.enabled=true", "jabiz.mail.from=jabiz@example.com",
    "jabiz.mail.base-url=https://app.example.com", "spring.mail.host=localhost", "spring.mail.port=3027"})
class MailIT extends SecurityItSupport {

    @RegisterExtension
    static final GreenMailExtension MAIL = new GreenMailExtension(new ServerSetup(3027, null, ServerSetup.PROTOCOL_SMTP));

    private static final Pattern VERIFY_LINK = Pattern.compile("/verify\\?token=([A-Za-z0-9_-]+)");
    private static final Pattern RESET_LINK = Pattern.compile("/reset\\?token=([A-Za-z0-9_-]+)");
    private static final Pattern UNSUBSCRIBE_LINK = Pattern.compile("/mail/unsubscribe\\?token=([A-Za-z0-9._-]+)");

    /**
     * Fails the first attempt for addresses starting with "flaky" before sending, and for those starting with "lost"
     * after the server took the message (a reply that never arrived); otherwise sends through the platform's sender.
     */
    @TestConfiguration
    static class FlakySender {

        static final Set<String> FAILED = ConcurrentHashMap.newKeySet();

        @Bean
        @Primary
        NotificationSender flakyMailSender(SmtpNotificationSender smtp) {
            return new NotificationSender() {
                @Override
                public void send(String to, String subject, String body) {
                    smtp.send(to, subject, body);
                }

                @Override
                public void send(MailMessage message) throws Exception {
                    if (message.to().startsWith("flaky") && FAILED.add(message.to())) {
                        throw new IllegalStateException("mail server busy");
                    }
                    smtp.send(message);
                    if (message.to().startsWith("lost") && FAILED.add(message.to())) {
                        throw new IllegalStateException("connection lost after DATA");
                    }
                }
            };
        }
    }

    @Autowired
    OutboxDeliverer deliverer;

    @Autowired
    JwtService jwt;

    private String caller() {
        return bearer(ItMailFixtures.PERMISSION);
    }

    @SuppressWarnings("unchecked")
    private String user(String address, String locale) {
        Map<String, Object> input = new HashMap<>();
        input.put("userName", unique("mail"));
        input.put("email", address);
        input.put("locale", locale);
        Map<String, Object> result = post("/api/processes/SEC_USER_CREATE/latest", admin(), input)
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        return (String) ((Map<String, Object>) result.get("output")).get("userId");
    }

    private Map<String, Object> send(String template, String userId, String address, String locale, String value,
        boolean fail, int status) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("template", template);
        input.put("userId", userId);
        input.put("address", address);
        input.put("locale", locale);
        input.put("value", value);
        input.put("fail", fail);
        return post("/api/processes/IT_MAIL_SEND/latest", caller(), input)
            .expectStatus().isEqualTo(status).expectBody(MAP).returnResult().getResponseBody();
    }

    private void send(String template, String userId, String value) {
        send(template, userId, null, null, value, false, 200);
    }

    private int deliver() {
        return deliverer.deliverPending().block();
    }

    private Map<String, Object> use(String process, String token, int status) {
        Map<String, Object> input = new HashMap<>();
        input.put("token", token);
        return post("/api/processes/" + process + "/latest", caller(), input)
            .expectStatus().isEqualTo(status).expectBody(MAP).returnResult().getResponseBody();
    }

    private static List<MimeMessage> to(String address) {
        return Arrays.stream(MAIL.getReceivedMessages()).filter(message -> {
            try {
                return message.getAllRecipients()[0].toString().equals(address);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).toList();
    }

    /** The text of the first part of the type, searched through nested multiparts. */
    private static String part(Part part, String type) throws Exception {
        if (part.isMimeType(type)) {
            return String.valueOf(part.getContent());
        }
        if (part.getContent() instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart child = multipart.getBodyPart(i);
                String found = part(child, type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static String link(Pattern pattern, MimeMessage message) throws Exception {
        Matcher matcher = pattern.matcher(part(message, "text/plain"));
        assertThat(matcher.find()).as("link %s in the mail", pattern).isTrue();
        return matcher.group(1);
    }

    private static List<Map<String, Object>> attempts(String address) {
        return query("SELECT a.attempt_no, a.outcome, a.detail FROM sys_mail_attempt a"
            + " JOIN sys_mail_message m ON m.message_id = a.message_id WHERE m.address = ?"
            + " ORDER BY m.created_time, a.attempt_no", address);
    }

    private static String address(String prefix) {
        return unique(prefix).toLowerCase() + "@example.com";
    }

    @Test
    void aMailIsSentOnceAfterTheCommitInTheRecipientsLanguageAsTextAndHtml() throws Exception {
        String address = address("ann");
        String user = user(address, "ja");
        send("verify", user, "<Ann & co>");

        // Queued in the process's transaction, sent only by the delivery.
        assertThat(query("SELECT template, category, locale, params FROM sys_mail_message WHERE address = ?", address))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("template", "it.verify")
                .containsEntry("category", "TRANSACTIONAL").containsEntry("locale", "ja")
                .containsEntry("params", "{\"userName\":\"<Ann & co>\"}"));
        assertThat(to(address)).isEmpty();

        deliver();
        assertThat(attempts(address)).extracting(row -> row.get("outcome")).containsExactly("SENT");
        MimeMessage mail = to(address).getFirst();
        assertThat(mail.getSubject()).isEqualTo("<Ann & co> さん、メールアドレスを確認してください");
        String text = part(mail, "text/plain");
        String html = part(mail, "text/html");
        assertThat(text).contains("<Ann & co> さん", "https://app.example.com/verify?token=");
        assertThat(html).contains("<strong>&lt;Ann &amp; co&gt;</strong>",
            "href=\"https://app.example.com/verify?token=");

        // Delivered once: the consumer has consumed the event, and a done message is never sent again.
        deliver();
        assertThat(to(address)).hasSize(1);
        assertThat(attempts(address)).hasSize(1);

        // Explicitly in English, to an address of its own.
        String other = address("ann-en");
        send("verify", user, other, "en", "Ann", false, 200);
        deliver();
        assertThat(to(other)).singleElement().satisfies(message ->
            assertThat(message.getSubject()).isEqualTo("Verify your address, Ann"));
    }

    @Test
    void aRolledBackProcessQueuesAndSendsNothing() {
        String address = address("rolled");
        String user = user(address, null);
        Map<String, Object> problem = send("verify", user, null, null, "x", true, 422);
        assertThat(ruleCode(problem)).isEqualTo("INVALID_VALUE");

        deliver();
        assertThat(query("SELECT * FROM sys_mail_message WHERE address = ?", address)).isEmpty();
        assertThat(to(address)).isEmpty();
    }

    @Test
    void aUserWithoutAnAddressIsAViolation() {
        String user = user(null, null);
        assertThat(ruleCode(send("verify", user, null, null, "x", false, 422))).isEqualTo("MAIL_NO_ADDRESS");
    }

    @Test
    void aFailedAttemptIsRecordedAndRetriedByTheOutboxUntilSent() throws Exception {
        String address = address("flaky");
        send("verify", user(address, "en"), "Bob");

        deliver();
        assertThat(attempts(address)).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("outcome", "FAILED").extractingByKey("detail").asString().contains("mail server busy"));
        assertThat(to(address)).isEmpty();
        assertThat(query("SELECT * FROM sys_mail_token WHERE address = ?", address)).isEmpty();

        // Not before the outbox's backoff.
        deliver();
        assertThat(attempts(address)).hasSize(1);
        clock.advance(Duration.ofSeconds(10));
        deliver();
        assertThat(attempts(address)).extracting(row -> row.get("outcome")).containsExactly("FAILED", "SENT");
        assertThat(to(address)).hasSize(1);

        clock.advance(Duration.ofMinutes(5));
        deliver();
        assertThat(to(address)).hasSize(1);
        assertThat(use("IT_MAIL_TOKEN_USE", link(VERIFY_LINK, to(address).getFirst()), 200))
            .extractingByKey("output").asString().contains(address);
    }

    @Test
    @SuppressWarnings("unchecked")
    void tokensAreStoredAsHashesOnlyAndGoodOnce() throws Exception {
        String address = address("token");
        String user = user(address, "en");
        send("verify", user, "Cy");
        deliver();
        String token = link(VERIFY_LINK, to(address).getFirst());

        assertThat(query("SELECT purpose, user_id::text AS user_id FROM sys_mail_token WHERE token_hash = ?",
            SingleUseSecrets.sha256Hex(token))).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("purpose", "verify").containsEntry("user_id", user));
        // The token itself is nowhere in the database: not as a hash key, not in a message, an event or an operation.
        assertThat(query("SELECT * FROM sys_mail_token WHERE token_hash = ?", token)).isEmpty();
        assertThat(query("SELECT 1 FROM sys_mail_message WHERE params LIKE ?", "%" + token + "%")).isEmpty();
        assertThat(query("SELECT 1 FROM sys_outbox_event WHERE payload::text LIKE ?", "%" + token + "%")).isEmpty();
        assertThat(query("SELECT 1 FROM op_process WHERE input_summary::text LIKE ?", "%" + token + "%")).isEmpty();

        // Of another purpose: refused, and still good for its own.
        assertThat(ruleCode(use("IT_MAIL_RESET_USE", token, 422))).isEqualTo("TOKEN_INVALID");
        Map<String, Object> used = use("IT_MAIL_TOKEN_USE", token, 200);
        assertThat(used).extractingByKey("output").satisfies(output ->
            assertThat((Map<String, Object>) output).containsEntry("userId", user).containsEntry("address", address));
        assertThat(ruleCode(use("IT_MAIL_TOKEN_USE", token, 422))).isEqualTo("TOKEN_INVALID");
        assertThat(ruleCode(use("IT_MAIL_TOKEN_USE", "no-such-token", 422))).isEqualTo("TOKEN_INVALID");
        assertThat(ruleCode(use("IT_MAIL_TOKEN_USE", null, 422))).isEqualTo("TOKEN_INVALID");
    }

    @Test
    void tokensExpire() throws Exception {
        String address = address("expire");
        send("reset", user(address, "en"), null);
        deliver();
        String token = link(RESET_LINK, to(address).getFirst());

        clock.advance(Duration.ofHours(1));
        assertThat(ruleCode(use("IT_MAIL_RESET_USE", token, 422))).isEqualTo("TOKEN_INVALID");
    }

    @Test
    void aLaterMailSupersedesTheTokensOfEarlierOnes() throws Exception {
        String address = address("resend");
        String user = user(address, "en");
        send("verify", user, "Di");
        deliver();
        String first = link(VERIFY_LINK, to(address).getFirst());
        send("verify", user, "Di");
        deliver();
        String second = link(VERIFY_LINK, to(address).get(1));

        assertThat(ruleCode(use("IT_MAIL_TOKEN_USE", first, 422))).isEqualTo("TOKEN_INVALID");
        use("IT_MAIL_TOKEN_USE", second, 200);
    }

    @Test
    void aRetryDrawsNewTokensAndTheTokensOfAFailedAttemptAreWorthless() throws Exception {
        String address = address("lost");
        send("verify", user(address, "en"), "Ed");
        deliver();
        // The server took the message, but the attempt failed: its token was never stored.
        assertThat(attempts(address)).extracting(row -> row.get("outcome")).containsExactly("FAILED");
        String lost = link(VERIFY_LINK, to(address).getFirst());

        clock.advance(Duration.ofSeconds(10));
        deliver();
        assertThat(attempts(address)).extracting(row -> row.get("outcome")).containsExactly("FAILED", "SENT");
        String sent = link(VERIFY_LINK, to(address).get(1));
        assertThat(sent).isNotEqualTo(lost);
        assertThat(ruleCode(use("IT_MAIL_TOKEN_USE", lost, 422))).isEqualTo("TOKEN_INVALID");
        use("IT_MAIL_TOKEN_USE", sent, 200);
    }

    @Test
    void notificationsCanBeTurnedOffThroughTheirLinkTransactionalMailCannot() throws Exception {
        String address = address("news");
        String user = user(address, "en");
        send("news", user, "apples");
        deliver();
        MimeMessage news = to(address).getFirst();
        assertThat(news.getSubject()).isEqualTo("News about apples");
        assertThat(part(news, "text/html")).contains("href=\"https://app.example.com/mail/unsubscribe?token=");
        String token = link(UNSUBSCRIBE_LINK, news);

        // Anonymous; repeating it changes nothing.
        post("/api/auth/mail/unsubscribe", null, Map.of("token", token)).expectStatus().isNoContent();
        post("/api/auth/mail/unsubscribe", null, Map.of("token", token)).expectStatus().isNoContent();
        assertThat(query("SELECT subscribed FROM sec_user_mail_preference_version WHERE user_id = ?::uuid",
            user)).extracting(row -> row.get("subscribed")).containsExactly(false);

        send("news", user, "pears");
        send("verify", user, "Fay");
        deliver();
        assertThat(attempts(address)).extracting(row -> row.get("outcome"), row -> row.get("detail"))
            .containsExactly(org.assertj.core.groups.Tuple.tuple("SENT", null),
                org.assertj.core.groups.Tuple.tuple("SKIPPED", "UNSUBSCRIBED"),
                org.assertj.core.groups.Tuple.tuple("SENT", null));
        assertThat(to(address)).extracting(MailIT::subject)
            .containsExactly("News about apples", "Verify your address, Fay");

        // The user sees the choice in the account settings and may turn it on again.
        String self = TestTokens.bearer(jwt, user);
        assertThat(get("/api/auth/mail/preferences", self).expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody()).extractingByKey("templates").asList()
            .contains(Map.of("template", "it.news", "subscribed", false));
        post("/api/auth/mail/preferences", self, Map.of("template", "it.news", "subscribed", true))
            .expectStatus().isOk();
        send("news", user, "plums");
        deliver();
        assertThat(to(address)).extracting(MailIT::subject).contains("News about plums");

        // Transactional mail has no unsubscribe link, nor does a forged one turn it off.
        Map<String, Object> problem = post("/api/auth/mail/unsubscribe", null,
            Map.of("token", jwt.issueUnsubscribe(user, "it.verify"))).expectStatus().isEqualTo(422)
            .expectBody(MAP).returnResult().getResponseBody();
        assertThat(ruleCode(problem)).isEqualTo("TOKEN_INVALID");
        assertThat(ruleCode(post("/api/auth/mail/preferences", self, Map.of("template", "it.verify",
            "subscribed", false)).expectStatus().isEqualTo(422).expectBody(MAP).returnResult().getResponseBody()))
            .isEqualTo("INVALID_VALUE");
    }

    @Test
    void aTamperedUnsubscribeTokenIsRefused() {
        String user = user(address("tamper"), "en");
        String token = jwt.issueUnsubscribe(user, "it.news");
        String[] parts = token.split("\\.");
        String other = jwt.issueUnsubscribe(user(address("victim"), "en"), "it.news");
        String tampered = parts[0] + "." + other.split("\\.")[1] + "." + parts[2];

        for (String bad : List.of(tampered, token + "x", "", "not-a-token")) {
            Map<String, Object> problem = post("/api/auth/mail/unsubscribe", null, Map.of("token", bad))
                .expectStatus().isEqualTo(422).expectBody(MAP).returnResult().getResponseBody();
            assertThat(ruleCode(problem)).isEqualTo("TOKEN_INVALID");
        }
        // An access token is no unsubscribe token.
        assertThat(ruleCode(post("/api/auth/mail/unsubscribe", null,
            Map.of("token", TestTokens.bearer(jwt, user).substring("Bearer ".length())))
            .expectStatus().isEqualTo(422).expectBody(MAP).returnResult().getResponseBody()))
            .isEqualTo("TOKEN_INVALID");
        assertThat(query("SELECT 1 FROM sec_user_mail_preference_version WHERE user_id = ?::uuid", user)).isEmpty();
    }

    @Test
    void usersSetTheLanguageOfTheirMail() {
        String address = address("lang");
        String user = user(address, null);
        post("/api/auth/account/locale", TestTokens.bearer(jwt, user), Map.of("locale", "zh"))
            .expectStatus().isOk();
        send("verify", user, "Gu");
        deliver();
        assertThat(to(address)).extracting(MailIT::subject).containsExactly("Gu，请验证您的邮箱");
        // Only the platform's languages.
        post("/api/auth/account/locale", TestTokens.bearer(jwt, user), Map.of("locale", "fr"))
            .expectStatus().isBadRequest();
    }

    @Test
    void mailTablesAreNeverUpdatedOrDeleted() {
        SqlStatementLog.STATEMENTS.clear();
        String address = address("flaky-append");
        String user = user(address, "en");
        send("news", user, "figs");
        deliver();
        clock.advance(Duration.ofSeconds(10));
        deliver();
        assertThat(attempts(address)).extracting(row -> row.get("outcome")).containsExactly("FAILED", "SENT");
        post("/api/auth/mail/unsubscribe", null, Map.of("token", jwt.issueUnsubscribe(user, "it.news")))
            .expectStatus().isNoContent();
        assertThat(SqlStatementLog.STATEMENTS).noneMatch(sql -> sql.matches(
            "(?is).*(UPDATE|DELETE FROM)\\s+(sys_mail_\\w+|sec_user_mail_preference_version)\\b.*"));
        assertThatThrownBy(() -> execute("UPDATE sys_mail_attempt SET outcome = 'SENT' WHERE detail IS NOT NULL"))
            .hasMessageContaining("append-only");
        assertThatThrownBy(() -> execute("DELETE FROM sys_mail_token"))
            .hasMessageContaining("append-only");
    }

    private static String subject(MimeMessage message) {
        try {
            return message.getSubject();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
