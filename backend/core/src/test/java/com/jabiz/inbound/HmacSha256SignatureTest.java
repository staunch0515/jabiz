package com.jabiz.inbound;

import com.jabiz.webhook.WebhookSignature;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HmacSha256SignatureTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final String OTHER = "fedcba9876543210fedcba9876543210";
    private static final long T = 1_700_000_000L;
    private static final Instant NOW = Instant.ofEpochSecond(T);
    private static final Duration TOLERANCE = Duration.ofMinutes(5);
    private static final byte[] BODY = "{\"eventType\":\"x\"}".getBytes(StandardCharsets.UTF_8);

    private final HmacSha256Signature scheme = new HmacSha256Signature();

    private InboundVerification verify(Map<String, List<String>> headers, List<String> secrets, Instant now) {
        return scheme.verify(InboundHeaders.of(headers), BODY, secrets, now, TOLERANCE);
    }

    private static Map<String, List<String>> headers(String timestamp, String signature) {
        return Map.of("X-Jabiz-Timestamp", List.of(timestamp), "X-Jabiz-Signature", List.of(signature));
    }

    @Test
    void acceptsWhatTheOutboundWebhookSigns() {
        String signature = WebhookSignature.sign(SECRET, T, BODY);
        InboundVerification result = verify(headers(Long.toString(T), signature), List.of(SECRET), NOW);
        assertThat(result.accepted()).isTrue();
        assertThat(result.signedTime()).isEqualTo(NOW);
        assertThat(scheme.name()).isEqualTo("hmac-sha256");
        // Round trip: what the scheme signs is what the outbound webhook sends.
        assertThat(scheme.sign(SECRET, T, BODY)).containsEntry("X-Jabiz-Timestamp", Long.toString(T))
            .containsEntry("X-Jabiz-Signature", signature);
    }

    @Test
    void anyConfiguredSecretMayHaveSigned() {
        String signature = WebhookSignature.sign(OTHER, T, BODY);
        assertThat(verify(headers(Long.toString(T), signature), List.of(SECRET, OTHER), NOW).accepted()).isTrue();
        assertThat(verify(headers(Long.toString(T), signature), List.of(SECRET), NOW).reason())
            .isEqualTo(InboundVerification.Reason.NO_MATCH);
        assertThat(WebhookSignature.matchesAny(List.of(SECRET, OTHER), T, BODY, signature)).isTrue();
        assertThat(WebhookSignature.matchesAny(List.of(), T, BODY, signature)).isFalse();
        assertThat(WebhookSignature.matchesAny(List.of(SECRET), T, BODY, null)).isFalse();
    }

    @Test
    void refusesMissingOrMalformedHeaders() {
        String signature = WebhookSignature.sign(SECRET, T, BODY);
        assertThat(verify(Map.of("X-Jabiz-Signature", List.of(signature)), List.of(SECRET), NOW).reason())
            .isEqualTo(InboundVerification.Reason.MISSING_HEADER);
        assertThat(verify(Map.of("X-Jabiz-Timestamp", List.of(Long.toString(T))), List.of(SECRET), NOW).reason())
            .isEqualTo(InboundVerification.Reason.MISSING_HEADER);
        assertThat(verify(headers("soon", signature), List.of(SECRET), NOW).reason())
            .isEqualTo(InboundVerification.Reason.MALFORMED);
        assertThat(verify(Map.of("X-Jabiz-Timestamp", List.of(Long.toString(T), Long.toString(T)),
            "X-Jabiz-Signature", List.of(signature)), List.of(SECRET), NOW).reason())
            .isEqualTo(InboundVerification.Reason.MALFORMED);
        assertThat(verify(Map.of("X-Jabiz-Timestamp", List.of(Long.toString(T)),
            "X-Jabiz-Signature", List.of(signature, signature)), List.of(SECRET), NOW).reason())
            .isEqualTo(InboundVerification.Reason.MALFORMED);
        assertThat(verify(headers(Long.toString(T), "sha256=abc"), List.of(SECRET), NOW).reason())
            .isEqualTo(InboundVerification.Reason.NO_MATCH);
    }

    @Test
    void timestampsOutsideTheToleranceAreRefused() {
        String signature = WebhookSignature.sign(SECRET, T, BODY);
        assertThat(verify(headers(Long.toString(T), signature), List.of(SECRET), NOW.plus(TOLERANCE).plusSeconds(1))
            .reason()).isEqualTo(InboundVerification.Reason.TOO_OLD);
        assertThat(verify(headers(Long.toString(T), signature), List.of(SECRET), NOW.minus(TOLERANCE).minusSeconds(1))
            .reason()).isEqualTo(InboundVerification.Reason.TOO_NEW);
    }

    @Test
    void headersAreLookedUpRegardlessOfCase() {
        InboundHeaders headers = InboundHeaders.of(Map.of("x-jabiz-timestamp", List.of("1")));
        assertThat(headers.values("X-JABIZ-TIMESTAMP")).containsExactly("1");
        assertThat(headers.values("missing")).isEmpty();
        assertThat(InboundHeaders.of(null).values("x")).isEmpty();
    }
}
