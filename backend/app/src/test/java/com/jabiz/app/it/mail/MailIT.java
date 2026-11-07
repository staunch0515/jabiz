package com.jabiz.app.it.mail;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
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
import com.jabiz.runtime.task.MailSenderTimeouts;
import com.jabiz.runtime.test.MutableClock;
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
    "jabiz.mail.base-url=https://app.example.com", "spring.mail.host=localhost", "spring.mail.port=3027",
    // The portal signs in by mailed link and opens accounts (decision D54): an application's invitation (D55) uses it.
    // With registration open, the administration must name its roles (SignInEntries).
    "jabiz.security.entries.portal.self-registration=true", "jabiz.security.entries.portal.email-link=true",
    "jabiz.security.entries.admin.accepted-roles=IT_BACK_OFFICE",
    // The shop's own server (decision D56) is a second local server with a self-signed certificate: tests only.
    "jabiz.mail.senders.trust-all-for-tests=true"})
class MailIT extends SecurityItSupport {

    @RegisterExtension
    static final GreenMailExtension MAIL = new GreenMailExtension(new ServerSetup(3027, null, ServerSetup.PROTOCOL_SMTP));

    /** The shop's own server (decision D56): SMTPS with a login the shop knows. */
    @RegisterExtension
    static final GreenMailExtension SHOP_MAIL = new GreenMailExtension(new ServerSetup(3465, null,
        ServerSetup.PROTOCOL_SMTPS)).withConfiguration(GreenMailConfiguration.aConfig()
        .withUser("shop@example.com", "shop", "shop-secret-1"));

    private static final Pattern VERIFY_LINK = Pattern.compile("/verify\\?token=([A-Za-z0-9_-]+)");
    private static final Pattern RESET_LINK = Pattern.compile("/reset\\?token=([A-Za-z0-9_-]+)");
    private static final Pattern UNSUBSCRIBE_LINK = Pattern.compile("/mail/unsubscribe\\?token=([A-Za-z0-9._-]+)");
    private static final Pattern SIGN_IN_LINK = Pattern.compile(
        "https://app\\.example\\.com/portal/auth/email-link\\?entry=portal&token=([A-Za-z0-9_-]+)&to=([^)\\s]*)");

    /**
     * Fails the first attempt for addresses starting with "flaky" before sending, and for those starting with "lost"
     * after the server took the message (a reply that never arrived); otherwise sends through the platform's sender.
     */
    @TestConfiguration
    static class FlakySender {

        static final Set<String> FAILED = ConcurrentHashMap.newKeySet();

        @Bean
        @Primary
        NotificationSender flakyMailSender(SmtpNotificationSender smtp, MutableClock clock) {
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
                    if (message.to().startsWith("slow")) {
                        clock.advance(Duration.ofMinutes(1));
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

    @Autowired
    MutableClock clock;

    @Autowired
    org.springframework.context.ApplicationContext beans;

    private String caller() {
        return bearer(ItMailFixtures.PERMISSION);
    }

    /** The portal's registration role (application.yml): a sign-in link to a new address opens an account with it. */
    @org.junit.jupiter.api.BeforeEach
    void customerRole() {
        if (query("SELECT 1 FROM sec_role_version WHERE role_code = ?", "CUSTOMER").isEmpty()) {
            createRole("CUSTOMER", com.jabiz.app.commerce.PortalOrders.PERMISSION);
        }
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

    /** An invitation (it.invite, decision D55) to a plain address, with a sign-in link at {@code entry} to {@code to}. */
    private Map<String, Object> invite(String address, String entry, String to, int status) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("template", "invite");
        input.put("address", address);
        input.put("locale", "en");
        input.put("value", "the quiz");
        input.put("entry", entry);
        input.put("to", to);
        return post("/api/processes/IT_MAIL_SEND/latest", caller(), input)
            .expectStatus().isEqualTo(status).expectBody(MAP).returnResult().getResponseBody();
    }

    private Map<String, Object> openLink(String token) {
        return post("/api/auth/email-link/confirm", null, Map.of("token", token)).expectStatus().isOk()
            .expectBody(MAP).returnResult().getResponseBody();
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

    /** Records a server of the shop's through IT_MAIL_SERVER_SET; the password is sealed on the way. */
    private void shopServer(String key, String password, boolean verified) {
        shopServer(key, 3465, "SMTPS", password, verified);
    }

    private void shopServer(String key, int port, String tls, String password, boolean verified) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("key", key);
        input.put("host", "localhost");
        input.put("port", port);
        input.put("tls", tls);
        input.put("username", "shop");
        input.put("password", password);
        input.put("fromAddress", "shop@example.com");
        input.put("fromName", "The Shop");
        input.put("verified", verified);
        post("/api/processes/IT_MAIL_SERVER_SET/latest", caller(), input).expectStatus().isOk();
    }

    /** A mail of it.via to {@code address}, through the shop's server {@code via}. */
    private Map<String, Object> sendVia(String address, String via, int status) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("template", "via");
        input.put("address", address);
        input.put("locale", "en");
        input.put("value", "the autumn menu");
        input.put("via", via);
        return post("/api/processes/IT_MAIL_SEND/latest", caller(), input)
            .expectStatus().isEqualTo(status).expectBody(MAP).returnResult().getResponseBody();
    }

    private static List<MimeMessage> received(GreenMailExtension server, String address) {
        return Arrays.stream(server.getReceivedMessages()).filter(message -> {
            try {
                return message.getAllRecipients()[0].toString().equals(address);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).toList();
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
    void aMailLeavesThroughTheApplicationsOwnServerInItsName() throws Exception {
        // Decision D56: the password is stored sealed, never as given.
        shopServer("shop-ok", "shop-secret-1", true);
        assertThat(query("SELECT f_secret FROM it_mail_server WHERE f_key = ?", "shop-ok")).singleElement()
            .satisfies(row -> assertThat(String.valueOf(row.get("f_secret"))).startsWith("s1:")
                .doesNotContain("shop-secret-1"));
        String address = address("via");
        sendVia(address, "shop-ok", 200);
        assertThat(query("SELECT sender_key FROM sys_mail_message WHERE address = ?", address)).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("sender_key", "shop-ok"));

        deliver();
        assertThat(attempts(address)).extracting(row -> row.get("outcome")).containsExactly("SENT");
        assertThat(to(address)).as("nothing through the platform's server").isEmpty();
        MimeMessage mail = received(SHOP_MAIL, address).getFirst();
        assertThat(mail.getFrom()[0].toString()).isEqualTo("The Shop <shop@example.com>");
        assertThat(mail.getSubject()).isEqualTo("From the shop: the autumn menu");
        assertThat(part(mail, "text/plain")).contains("The shop writes about the autumn menu.");

        // A blank key is a violation of the process, not a mail through the platform's server.
        assertThat(ruleCode(sendVia(address("via-none"), " ", 422))).isEqualTo("MAIL_SENDER_MISSING");
    }

    @Test
    void aServerThatRefusesOrIsUnknownFailsTheAttemptAndNeverFallsBackToThePlatforms() throws Exception {
        shopServer("shop-wrong", "not-the-password", true);
        shopServer("shop-unverified", "shop-secret-1", false);
        String wrong = address("via-wrong");
        String unknown = address("via-unknown");
        String unverified = address("via-unverified");
        sendVia(wrong, "shop-wrong", 200);
        sendVia(unknown, "shop-nowhere", 200);
        sendVia(unverified, "shop-unverified", 200);

        // The outbox stops a consumer's batch at its first failure: one delivery per refused mail.
        deliver();
        deliver();
        deliver();
        // The server's own answer is the attempt's detail: the shop sees why.
        assertThat(attempts(wrong)).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("outcome", "FAILED").extractingByKey("detail").asString().containsIgnoringCase("auth"));
        assertThat(attempts(unknown)).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("outcome", "FAILED").extractingByKey("detail").asString().contains("does not exist"));
        assertThat(attempts(unverified)).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("outcome", "FAILED").extractingByKey("detail").asString().contains("not usable"));
        assertThat(to(wrong)).isEmpty();
        assertThat(to(unknown)).isEmpty();
        assertThat(to(unverified)).isEmpty();
        assertThat(received(SHOP_MAIL, wrong)).isEmpty();

        // Corrected, the outbox's retries go through the shop's server; the mails were never lost.
        shopServer("shop-wrong", "shop-secret-1", true);
        shopServer("shop-nowhere", "shop-secret-1", true);
        shopServer("shop-unverified", "shop-secret-1", true);
        clock.advance(Duration.ofMinutes(1));
        deliver();
        for (String address : List.of(wrong, unknown, unverified)) {
            assertThat(attempts(address)).extracting(row -> row.get("outcome")).containsExactly("FAILED", "SENT");
            assertThat(received(SHOP_MAIL, address)).hasSize(1);
            assertThat(to(address)).isEmpty();
        }
    }

    /** TLS is required: a server that offers only plaintext (the platform's test server here) gets no credentials. */
    @Test
    void aServerWithoutTlsIsRefusedBeforeAnyCredentialIsSent() throws Exception {
        shopServer("shop-plain", 3027, "STARTTLS", "shop-secret-1", true);
        String address = address("via-plain");
        sendVia(address, "shop-plain", 200);

        deliver();
        assertThat(attempts(address)).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("outcome", "FAILED").extractingByKey("detail").asString().containsIgnoringCase("STARTTLS"));
        assertThat(to(address)).isEmpty();

        // Moved to a server with TLS, the retry goes out.
        shopServer("shop-plain", 3465, "SMTPS", "shop-secret-1", true);
        clock.advance(Duration.ofMinutes(1));
        deliver();
        assertThat(attempts(address)).extracting(row -> row.get("outcome")).containsExactly("FAILED", "SENT");
        assertThat(received(SHOP_MAIL, address)).hasSize(1);
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
        // Its token was stored before the send; the retry's token supersedes it.
        assertThat(query("SELECT * FROM sys_mail_token WHERE address = ?", address)).hasSize(1);

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
            .contains(Map.of("template", "it.news", "subscribed", false, "displayName", "News", "otherEntry", false));
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

    /** A notification declared for one entry links to that entry's unsubscribe page (decision D52 item 3). */
    @Test
    void aTemplateOfOneEntryLinksToThatEntrysPage() throws Exception {
        String address = address("portal-link");
        String user = user(address, "en");
        send("offers", user, "jam");
        deliver();
        MimeMessage offers = to(address).getFirst();
        assertThat(part(offers, "text/html"))
            .contains("href=\"https://app.example.com/portal/mail/unsubscribe?token=");
        assertThat(part(offers, "text/plain")).contains("https://app.example.com/portal/mail/unsubscribe?token=");
        // The same token and the same endpoint as every unsubscribe link: the page only posts it.
        post("/api/auth/mail/unsubscribe", null, Map.of("token", link(UNSUBSCRIBE_LINK, offers)))
            .expectStatus().isNoContent();
        assertThat(query("SELECT template, subscribed FROM sec_user_mail_preference_version WHERE user_id = ?::uuid",
            user)).singleElement().satisfies(row -> assertThat(row).containsEntry("template", "it.offers")
                .containsEntry("subscribed", false));
    }

    @Test
    void templatesOfAllEntriesKeepTheGlobalPath() throws Exception {
        String address = address("global-link");
        send("news", user(address, "en"), "figs");
        deliver();
        String html = part(to(address).getFirst(), "text/html");
        assertThat(html).contains("href=\"https://app.example.com/mail/unsubscribe?token=").doesNotContain("/portal/");
    }

    /** Links mailed before the entry had its page lead to the application's page, which still unsubscribes. */
    @Test
    void anOldLinkStillUnsubscribes() {
        String user = user(address("old-link"), "en");
        post("/api/auth/mail/unsubscribe", null, Map.of("token", jwt.issueUnsubscribe(user, "it.offers")))
            .expectStatus().isNoContent();
        assertThat(query("SELECT subscribed FROM sec_user_mail_preference_version WHERE user_id = ?::uuid"
            + " AND template = 'it.offers'", user)).extracting(row -> row.get("subscribed")).containsExactly(false);
    }

    @Test
    void aNotificationForOneEntryIsNotSentToAUserWhoTurnedItOff() throws Exception {
        // it.offers is the portal's (decision D49); the user turned it off at the portal.
        String address = address("offers");
        String user = user(address, "en");
        send("offers", user, "tea");
        deliver();
        assertThat(to(address)).extracting(MailIT::subject).containsExactly("Offers for tea");

        String portal = "Bearer " + jwt.issue(new com.jabiz.runtime.context.Actor(user, null, Set.of(), Set.of(),
            jwt.clock().instant(), null, "portal", true)).token();
        post("/api/auth/mail/preferences", portal, Map.of("template", "it.offers", "subscribed", false))
            .expectStatus().isOk();
        send("offers", user, "cake");
        deliver();
        assertThat(attempts(address)).extracting(row -> row.get("outcome"), row -> row.get("detail"))
            .containsExactly(org.assertj.core.groups.Tuple.tuple("SENT", null),
                org.assertj.core.groups.Tuple.tuple("SKIPPED", "UNSUBSCRIBED"));
        assertThat(to(address)).extracting(MailIT::subject).containsExactly("Offers for tea");
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
    void aMailTheServerTookIsNotSentAgainWhenTheDeliveryFailsAfterwards() throws Exception {
        // The delivery's commit fails once, after the send (a deferred trigger on its consumption row).
        execute("CREATE TABLE it_fail_mail_commit (address varchar(320))");
        execute("""
            CREATE FUNCTION it_fail_mail_commit() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN
                IF NEW.consumer = 'jabiz.mail' AND EXISTS (SELECT 1 FROM it_fail_mail_commit) THEN
                    RAISE EXCEPTION 'commit refused by the test';
                END IF;
                RETURN NULL;
            END $$""");
        execute("CREATE CONSTRAINT TRIGGER it_fail_mail_commit AFTER INSERT ON sys_event_consumption"
            + " DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION it_fail_mail_commit()");
        String address = address("commit");
        execute("INSERT INTO it_fail_mail_commit VALUES (?)", address);
        try {
            send("verify", user(address, "en"), "Hal");
            deliver();
            assertThat(to(address)).hasSize(1);
            assertThat(attempts(address)).extracting(row -> row.get("outcome")).containsExactly("SENT");
            assertThat(query("SELECT 1 FROM sys_outbox_attempt WHERE consumer = 'jabiz.mail' AND error LIKE ?",
                "%commit refused%")).isNotEmpty();
        } finally {
            execute("DROP TRIGGER it_fail_mail_commit ON sys_event_consumption");
        }
        // The retry finds the mail sent: no second copy, and the token of the one that went out is good.
        clock.advance(Duration.ofMinutes(1));
        deliver();
        assertThat(to(address)).hasSize(1);
        assertThat(attempts(address)).hasSize(1);
        use("IT_MAIL_TOKEN_USE", link(VERIFY_LINK, to(address).getFirst()), 200);
    }

    @Test
    void aSentAttemptIsTimedAfterTheSend() {
        String address = address("slow");
        send("verify", user(address, "en"), "Ivy");
        java.time.Instant queued = clock.instant();
        deliver();
        assertThat(query("SELECT a.attempted_time FROM sys_mail_attempt a JOIN sys_mail_message m"
            + " ON m.message_id = a.message_id WHERE m.address = ?", address)).singleElement()
            .satisfies(row -> assertThat(((java.sql.Timestamp) row.get("attempted_time")).toInstant())
                .isEqualTo(queued.plus(Duration.ofMinutes(1))));
    }

    @Test
    void theMailServersTimeoutsAreSetOnTheSender() throws Exception {
        // Spring's mail classes are the runtime's own dependency: reached through the bean.
        Object sender = beans.getBean("mailSender");
        assertThat((java.util.Properties) sender.getClass().getMethod("getJavaMailProperties").invoke(sender))
            .containsKeys(MailSenderTimeouts.PROPERTIES.toArray())
            .containsEntry("mail.smtp.connectiontimeout", "10000");
    }

    @Test
    void parametersAreStoredAsTheyAreAndSecretsOrOversizedOnesRefused() throws Exception {
        String address = address("param");
        String user = user(address, "en");
        String longName = "n".repeat(10_000);
        send("verify", user, longName);
        deliver();
        assertThat(part(to(address).getFirst(), "text/plain")).contains(longName);
        assertThat(query("SELECT params FROM sys_mail_message WHERE address = ?", address)).singleElement()
            .satisfies(row -> assertThat((String) row.get("params")).contains(longName));

        assertThat(ruleCode(send("verify", user, null, null, "secret:1234", false, 422)))
            .isEqualTo("MAIL_PARAM_SECRET");
        assertThat(ruleCode(send("verify", user, null, null, "x".repeat(17_000), false, 422)))
            .isEqualTo("MAIL_PARAMS_TOO_LARGE");
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

    /** An application's mail carries a one-time sign-in link (decision D55): the platform's processes open it. */
    @Test
    void anApplicationsMailMayCarryASignInLinkThatSignsInOrOpensAnAccount() throws Exception {
        String address = address("invited");
        invite(address, "portal", "/portal/p/42?ref=mail", 200);
        deliver();
        MimeMessage mail = to(address).getFirst();
        assertThat(mail.getSubject()).isEqualTo("You are invited to the quiz");
        String text = part(mail, "text/plain");
        assertThat(text).contains("https://app.example.com/portal/auth/email-link?entry=portal&token=")
            .contains("&to=%2Fportal%2Fp%2F42%3Fref%3Dmail");
        // Nothing opened before the mailbox is proven.
        assertThat(query("SELECT 1 FROM sec_user_version WHERE lower(email) = lower(?)", address)).isEmpty();

        String token = link(SIGN_IN_LINK, mail);
        assertThat(openLink(token)).containsEntry("status", "SIGNED_IN").containsKey("accessToken");
        List<Map<String, Object>> users = query("SELECT user_id, verified_email, password_hash FROM sec_user_version"
            + " WHERE lower(email) = lower(?) ORDER BY version_no DESC", address);
        assertThat(users).isNotEmpty();
        assertThat(users.getFirst()).containsEntry("verified_email", address).containsEntry("password_hash", null);
        String userId = String.valueOf(users.getFirst().get("user_id"));
        // Good once.
        assertThat(ruleCode(post("/api/auth/email-link/confirm", null, Map.of("token", token)).expectStatus()
            .isEqualTo(422).expectBody(MAP).returnResult().getResponseBody())).isEqualTo("TOKEN_INVALID");

        // Invited again, later (a link issued at the instant another was used would count as superseded, D54 item
        // 3): the link signs in the account the address has now, as a mail to a user would.
        clock.advance(java.time.Duration.ofMinutes(1));
        invite(address, "portal", "", 200);
        deliver();
        assertThat(openLink(link(SIGN_IN_LINK, to(address).get(1)))).containsEntry("status", "SIGNED_IN");
        assertThat(query("SELECT DISTINCT user_id FROM sec_user_version WHERE lower(email) = lower(?)", address))
            .singleElement().satisfies(row -> assertThat(String.valueOf(row.get("user_id"))).isEqualTo(userId));
    }

    /** The link signs in at an entry that signs in by mailed link only, and leads back within that entry's pages. */
    @Test
    void aSignInLinkIsRefusedForAnEntryWithoutLinkSignInOrAPathOutsideIt() {
        String address = address("refused");
        assertThat(ruleCode(invite(address, "admin", "", 422))).isEqualTo("MAIL_SIGN_IN_LINK");
        assertThat(ruleCode(invite(address, "no-such-entry", "", 422))).isEqualTo("MAIL_SIGN_IN_LINK");
        assertThat(ruleCode(invite(address, "portal", "/elsewhere/p/1", 422))).isEqualTo("MAIL_SIGN_IN_LINK");
        assertThat(ruleCode(invite(address, "portal", "https://evil.example.com/", 422))).isEqualTo("MAIL_SIGN_IN_LINK");
        assertThat(query("SELECT 1 FROM sys_mail_message WHERE address = ?", address)).isEmpty();
    }

    /** A notification to a plain address (decision D55): its link opts the address out, whatever its case. */
    @Test
    void anAddressWithoutAUserOptsOutOfANotificationThroughItsLink() throws Exception {
        String address = address("optout");
        invite(address, "portal", "", 200);
        deliver();
        MimeMessage first = to(address).getFirst();
        String token = link(UNSUBSCRIBE_LINK, first);
        // Anonymous; repeating it changes nothing.
        post("/api/auth/mail/unsubscribe", null, Map.of("token", token)).expectStatus().isNoContent();
        post("/api/auth/mail/unsubscribe", null, Map.of("token", token)).expectStatus().isNoContent();
        assertThat(query("SELECT address, template FROM sys_mail_address_optout WHERE address = ?",
            address.toLowerCase(java.util.Locale.ROOT))).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("template", "it.invite"));

        // Not mailed again, in whatever case the address is written; transactional mail to it still goes out.
        invite(address, "portal", "", 200);
        invite(address.toUpperCase(java.util.Locale.ROOT), "portal", "", 200);
        send("verify", null, address, "en", "Ivy", false, 200);
        deliver();
        assertThat(attempts(address)).extracting(row -> row.get("outcome"), row -> row.get("detail"))
            .containsExactly(org.assertj.core.groups.Tuple.tuple("SENT", null),
                org.assertj.core.groups.Tuple.tuple("SKIPPED", "UNSUBSCRIBED"),
                org.assertj.core.groups.Tuple.tuple("SENT", null));
        assertThat(attempts(address.toUpperCase(java.util.Locale.ROOT))).extracting(row -> row.get("detail"))
            .containsExactly("UNSUBSCRIBED");
        assertThat(to(address)).extracting(MailIT::subject).containsExactly("You are invited to the quiz",
            "Verify your address, Ivy");
    }

    /**
     * A notification to plain addresses only (phase 17r): its link opts the address out as any address's does, and a
     * process that sends it to a user is a defect, which fails the process and sends nothing.
     */
    @Test
    void aMailForAddressesOnlyOptsOutByItsLinkAndIsNeverSentToAUser() throws Exception {
        String address = address("prospect");
        send("prospect", null, address, "en", "the shop", false, 200);
        deliver();
        String token = link(UNSUBSCRIBE_LINK, to(address).getFirst());
        post("/api/auth/mail/unsubscribe", null, Map.of("token", token)).expectStatus().isNoContent();
        assertThat(query("SELECT template FROM sys_mail_address_optout WHERE address = ?",
            address.toLowerCase(java.util.Locale.ROOT))).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("template", "it.prospect"));

        String user = user(address("prospect-user"), "en");
        send("prospect", user, null, "en", "the shop", false, 500);
        assertThat(query("SELECT 1 FROM sys_mail_message WHERE user_id = ?::uuid", user)).isEmpty();
    }
}
