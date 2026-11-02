package com.jabiz.runtime.push;

import com.jabiz.runtime.test.MutableClock;
import com.jabiz.runtime.test.TestApnsService;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ApnsSenderTest {

    static final TestApnsService APNS = new TestApnsService();

    @AfterAll
    static void stop() {
        APNS.close();
    }

    private static PushProperties.Apns settings() {
        return new PushProperties.Apns("TEAM123456", "KEY1234567", APNS.privateKey(), "com.example.app", null,
            APNS.base());
    }

    @Test
    void applesAnswersMeanDeliveredGoneOrTryAgain() {
        ApnsSender sender = new ApnsSender(settings(), java.time.Clock.systemUTC());
        assertThat(sender.outcome(200, "", null)).isEqualTo(WebPushSender.Outcome.DELIVERED);
        assertThat(sender.outcome(410, "{\"reason\":\"Unregistered\"}", null)).isEqualTo(WebPushSender.Outcome.GONE);
        // A token of the other environment, or of another bundle, says the settings are wrong, not that the device is
        // gone: no iPhone is forgotten for it.
        assertThat(sender.outcome(400, "{\"reason\":\"BadDeviceToken\"}", null))
            .isEqualTo(WebPushSender.Outcome.FAILED);
        assertThat(sender.outcome(400, "{\"reason\":\"DeviceTokenNotForTopic\"}", null))
            .isEqualTo(WebPushSender.Outcome.FAILED);
        assertThat(sender.outcome(400, "{\"reason\":\"PayloadTooLarge\"}", null))
            .isEqualTo(WebPushSender.Outcome.FAILED);
        assertThat(sender.outcome(429, "{\"reason\":\"TooManyRequests\"}", null))
            .isEqualTo(WebPushSender.Outcome.FAILED);
        assertThat(sender.outcome(503, null, null)).isEqualTo(WebPushSender.Outcome.FAILED);
        assertThat(ApnsSender.reason("{ \"reason\" : \"Unregistered\", \"timestamp\": 1 }")).isEqualTo("Unregistered");
        // Not a device token: no request, the device is forgotten.
        assertThat(sender.send("not-a-token", new byte[0], null).block()).isEqualTo(WebPushSender.Outcome.GONE);
    }

    @Test
    void theProviderTokenIsTheTeamsKeptFortyMinutesAndSignedAgainWhenRefusedNotSooner() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-02T00:00:00Z"));
        ApnsSender sender = new ApnsSender(settings(), clock);
        ApnsSender.ProviderToken first = sender.providerToken();
        SignedJWT jwt = SignedJWT.parse(first.value());
        assertThat(jwt.getHeader().getAlgorithm().getName()).isEqualTo("ES256");
        assertThat(jwt.getHeader().getKeyID()).isEqualTo("KEY1234567");
        assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("TEAM123456");
        assertThat(jwt.getJWTClaimsSet().getIssueTime().toInstant()).isEqualTo(clock.instant());

        // Refused within 20 minutes (a wrong key, a clock off): kept, so that Apple is not asked to take a new token
        // with every notification.
        clock.advance(Duration.ofMinutes(10));
        sender.outcome(403, "{\"reason\":\"InvalidProviderToken\"}", first);
        assertThat(sender.providerToken()).isEqualTo(first);
        clock.advance(Duration.ofMinutes(29));
        assertThat(sender.providerToken()).isEqualTo(first);
        clock.advance(Duration.ofMinutes(2));
        ApnsSender.ProviderToken second = sender.providerToken();
        assertThat(second).isNotEqualTo(first);
        // A late refusal of the old token leaves the new one alone.
        sender.outcome(403, "{\"reason\":\"ExpiredProviderToken\"}", first);
        assertThat(sender.providerToken()).isEqualTo(second);
        // The token in use, refused once it is 20 minutes old: the next notification signs a new one.
        clock.advance(Duration.ofMinutes(20));
        sender.outcome(403, "{\"reason\":\"ExpiredProviderToken\"}", second);
        assertThat(sender.providerToken()).isNotEqualTo(second);
    }

    @Test
    void concurrentSendsShareOneSignature() throws Exception {
        ApnsSender sender = new ApnsSender(settings(), java.time.Clock.systemUTC());
        java.util.Set<ApnsSender.ProviderToken> tokens = java.util.concurrent.ConcurrentHashMap.newKeySet();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < 8; i++) {
                pool.submit(() -> tokens.add(sender.providerToken()));
            }
        }
        assertThat(tokens).hasSize(1);
    }

    @Test
    void aNotificationReachesTheDeviceForTheAppsBundle() {
        ApnsSender sender = new ApnsSender(settings(), java.time.Clock.systemUTC());
        String token = APNS.deviceToken();
        assertThat(sender.send(token, "{\"aps\":{}}".getBytes(java.nio.charset.StandardCharsets.UTF_8), "n1").block())
            .isEqualTo(WebPushSender.Outcome.DELIVERED);
        assertThat(APNS.received(token)).singleElement().satisfies(received -> {
            assertThat(received.providerTokenValid()).isTrue();
            assertThat(received.topic()).isEqualTo("com.example.app");
            assertThat(received.collapseId()).isEqualTo("n1");
            assertThat(received.body()).isEqualTo("{\"aps\":{}}");
        });
        APNS.answer(token, 410, "Unregistered");
        assertThat(sender.send(token, new byte[0], null).block()).isEqualTo(WebPushSender.Outcome.GONE);
    }
}
