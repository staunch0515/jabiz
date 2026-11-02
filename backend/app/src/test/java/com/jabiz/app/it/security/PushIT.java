package com.jabiz.app.it.security;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;
import com.jabiz.context.RequestContext;
import com.jabiz.runtime.erasure.AccountErasure;
import com.jabiz.runtime.event.OutboxDeliverer;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.test.TestPushService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Push notifications (decision D69; ROADMAP phase 17k-a), against a test push service on a local port: a signed-in
 * user registers this browser's subscription for the session in use; a process's notification reaches it after the
 * commit, encrypted for it and signed with the VAPID key, in the user's language, with the page of the entry's
 * frontend; a template turned off, another entry's template, a rolled back process push nothing; a subscription the
 * push service says is gone, one of a session that ended and the devices of an erased account are forgotten; only
 * subscriptions of allowed push services with a subscription's keys are taken.
 */
@SpringBootTest(properties = {"jabiz.push.enabled=true", "jabiz.push.web-push.subject=mailto:push@example.com",
    "jabiz.push.web-push.allowed-hosts=localhost", SignUpItSupport.ENTRY_PROPERTIES_REGISTRATION,
    SignUpItSupport.ENTRY_PROPERTIES_LOOSE, SignUpItSupport.ENTRY_PROPERTIES_ADMIN})
@Import(SignUpItSupport.FixedTimeLimits.class)
class PushIT extends SignUpItSupport {

    @RegisterExtension
    static final GreenMailExtension MAIL = new GreenMailExtension(new ServerSetup(3035, null, ServerSetup.PROTOCOL_SMTP));

    static final String[] VAPID = TestPushService.vapidKeys();
    static final TestPushService PUSH = new TestPushService(VAPID[0]);
    static final String BASE = "/api/auth/account/push";

    @DynamicPropertySource
    static void keys(DynamicPropertyRegistry registry) {
        registry.add("jabiz.push.web-push.public-key", () -> VAPID[0]);
        registry.add("jabiz.push.web-push.private-key", () -> VAPID[1]);
    }

    @AfterAll
    static void stop() {
        PUSH.close();
    }

    @Autowired
    OutboxDeliverer deliverer;

    @Autowired
    ProcessExecutor executor;

    @Autowired
    JsonMapper json;

    @Override
    GreenMailExtension mail() {
        return MAIL;
    }

    /** A portal customer signed in at the portal: their id and session. */
    private record Customer(String userId, String email, Map<String, Object> session) {
        String bearer() {
            return bearerOf(session);
        }
    }

    private Customer customer() {
        String email = address("push");
        String userId = createUserWithEmail(email, email, PASSWORD);
        verifyEmail(userId);
        assign(userId, roleId(CUSTOMER), null);
        return new Customer(userId, email, session(email, PASSWORD, PORTAL));
    }

    private Map<String, Object> register(String bearer, TestPushService.Subscription subscription, int status) {
        Map<String, Object> body = new HashMap<>();
        body.put("kind", "webpush");
        body.put("endpoint", subscription.endpoint());
        body.put("p256dh", subscription.p256dh());
        body.put("auth", subscription.auth());
        return post(BASE + "/devices", bearer, body).expectStatus().isEqualTo(status).expectBody(MAP).returnResult()
            .getResponseBody();
    }

    private void send(String template, String userId, String value, String link, boolean fail) {
        Map<String, Object> input = new HashMap<>();
        input.put("template", template);
        input.put("userId", userId);
        input.put("value", value);
        input.put("link", link);
        input.put("fail", fail);
        post("/api/processes/IT_PUSH_SEND/latest", admin(), input).expectStatus()
            .isEqualTo(fail ? 500 : 200);
    }

    private void pushQueued() {
        deliverer.deliverPending().block();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> message(TestPushService.Received received) {
        return json.readValue(received.plaintext(), Map.class);
    }

    private static long devices(String userId) {
        return ((Number) query("SELECT count(*) AS n FROM sec_user_device WHERE user_id = ?::uuid", userId)
            .getFirst().get("n")).longValue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aNotificationReachesTheRegisteredBrowserEncryptedSignedAndInTheUsersLanguage() {
        Customer customer = customer();
        Map<String, Object> settings = get(BASE, customer.bearer()).expectStatus().isOk().expectBody(MAP)
            .returnResult().getResponseBody();
        assertThat(settings).containsEntry("enabled", true).containsEntry("webPushKey", VAPID[0]);
        assertThat((List<Map<String, Object>>) settings.get("templates")).extracting(t -> t.get("template"))
            .containsExactly("it.ping", "it.portal-ping", "commerce.order-shipped");

        TestPushService.Subscription browser = PUSH.subscribe();
        String deviceId = (String) register(customer.bearer(), browser, 200).get("deviceId");
        assertThat((List<Map<String, Object>>) get(BASE, customer.bearer()).expectStatus().isOk().expectBody(MAP)
            .returnResult().getResponseBody().get("devices")).singleElement().satisfies(device -> assertThat(device)
            .containsEntry("id", deviceId).containsEntry("kind", "webpush").containsEntry("entry", PORTAL)
            .containsEntry("current", true));
        // The subscription's endpoint and keys are secrets of the browser: not in the operation log, not in the audit.
        assertThat(query("SELECT 1 FROM op_process WHERE input_summary::text LIKE ?", "%" + browser.auth() + "%"))
            .isEmpty();
        assertThat(query("SELECT 1 FROM sys_audit_record WHERE changes::text LIKE ?", "%" + browser.p256dh() + "%"))
            .isEmpty();

        send("ping", customer.userId(), "the new quiz", "orders", false);
        // Nothing before the commit's outbox is delivered.
        assertThat(PUSH.received(browser)).isEmpty();
        pushQueued();
        assertThat(PUSH.received(browser)).singleElement().satisfies(received -> {
            assertThat(received.vapidValid()).isTrue();
            assertThat(received.encoding()).isEqualTo("aes128gcm");
            assertThat(received.ttl()).isEqualTo("86400");
            assertThat(message(received)).containsEntry("title", "Ping").containsEntry("body", "About the new quiz.")
                .containsEntry("url", "/portal/orders");
            // The notification's own tag, and the same topic: a copy sent again replaces it instead of adding one.
            assertThat((String) message(received).get("tag")).matches("[0-9a-f]{32}");
            assertThat(received.topic()).isEqualTo(message(received).get("tag"));
        });
    }

    @Test
    void aTemplateTurnedOffAnotherEntrysTemplateAndARolledBackProcessPushNothing() {
        Customer customer = customer();
        TestPushService.Subscription browser = PUSH.subscribe();
        register(customer.bearer(), browser, 200);

        post(BASE + "/preferences", customer.bearer(), Map.of("template", "it.ping", "subscribed", false))
            .expectStatus().isOk();
        send("ping", customer.userId(), "muted", null, false);
        send("ping", customer.userId(), "rolled back", null, true);
        pushQueued();
        assertThat(PUSH.received(browser)).isEmpty();

        // Back on: it arrives again.
        post(BASE + "/preferences", customer.bearer(), Map.of("template", "it.ping", "subscribed", true))
            .expectStatus().isOk();
        send("ping", customer.userId(), "again", null, false);
        pushQueued();
        assertThat(PUSH.received(browser)).hasSize(1);

        // A device of another entry does not receive the portal's notification.
        String loose = bearerOf(session(customer.email(), PASSWORD, LOOSE));
        TestPushService.Subscription desk = PUSH.subscribe();
        register(loose, desk, 200);
        send("portal", customer.userId(), "portal only", null, false);
        pushQueued();
        assertThat(PUSH.received(desk)).isEmpty();
        assertThat(PUSH.received(browser)).hasSize(2);
        // An unknown template is refused like any other name.
        post(BASE + "/preferences", customer.bearer(), Map.of("template", "it.none", "subscribed", false))
            .expectStatus().isEqualTo(422);
    }

    @Test
    void goneSubscriptionsEndedSessionsAndErasedAccountsAreForgotten() {
        Customer customer = customer();
        TestPushService.Subscription gone = PUSH.subscribe();
        register(customer.bearer(), gone, 200);
        PUSH.answer(gone, 410);
        send("ping", customer.userId(), "x", null, false);
        pushQueued();
        assertThat(devices(customer.userId())).isZero();

        // Signing out ends the session, and its device with the next notification.
        TestPushService.Subscription browser = PUSH.subscribe();
        register(customer.bearer(), browser, 200);
        post("/api/auth/logout", null, Map.of("refreshToken", customer.session().get("refreshToken")))
            .expectStatus().isNoContent();
        send("ping", customer.userId(), "after sign-out", null, false);
        pushQueued();
        assertThat(PUSH.received(browser)).isEmpty();
        assertThat(devices(customer.userId())).isZero();

        // Erasing the account deletes its devices.
        Customer erased = customer();
        register(erased.bearer(), PUSH.subscribe(), 200);
        asRequest(RequestContext.system(Locale.ENGLISH, "it-push"), executor.execute(AccountErasure.ERASE,
            new AccountErasure.EraseInput(UUID.fromString(erased.userId()), "it-push"))).block();
        assertThat(devices(erased.userId())).isZero();
    }

    @Test
    void onlySubscriptionsOfAllowedPushServicesWithASubscriptionsKeysAreTaken() {
        Customer customer = customer();
        TestPushService.Subscription browser = PUSH.subscribe();
        Map<String, Object> elsewhere = new HashMap<>(Map.of("kind", "webpush", "endpoint",
            "https://attacker.example.com/push/1", "p256dh", browser.p256dh(), "auth", browser.auth()));
        assertThat(ruleCode(post(BASE + "/devices", customer.bearer(), elsewhere).expectStatus().isEqualTo(422)
            .expectBody(MAP).returnResult().getResponseBody())).isEqualTo("PUSH_SUBSCRIPTION_INVALID");
        Map<String, Object> badKeys = new HashMap<>(Map.of("kind", "webpush", "endpoint", browser.endpoint(),
            "p256dh", "AAAA", "auth", browser.auth()));
        assertThat(ruleCode(post(BASE + "/devices", customer.bearer(), badKeys).expectStatus().isEqualTo(422)
            .expectBody(MAP).returnResult().getResponseBody())).isEqualTo("PUSH_SUBSCRIPTION_INVALID");

        // The same browser registered again by another account (signed out and in as someone else): it moves.
        register(customer.bearer(), browser, 200);
        Customer other = customer();
        register(other.bearer(), browser, 200);
        assertThat(devices(customer.userId())).isZero();
        assertThat(devices(other.userId())).isEqualTo(1);
        // Removed by its endpoint, as a browser that unsubscribes knows it.
        post(BASE + "/devices/remove", other.bearer(), Map.of("endpoint", browser.endpoint()))
            .expectStatus().isNoContent();
        assertThat(devices(other.userId())).isZero();
        post(BASE + "/devices/remove", other.bearer(), Map.of("endpoint", browser.endpoint()))
            .expectStatus().isNotFound();
    }
}
