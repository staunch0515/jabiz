package com.jabiz.inbound;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StripeSignatureTest {

    private static final String SECRET = "whsec_test_secret_0123456789abcdef";
    private static final String ROTATED = "whsec_rotated_secret_abcdef0123456789";
    private static final long T = 1_767_225_600L; // 2026-01-01T00:00:00Z
    private static final Instant NOW = Instant.ofEpochSecond(T);
    private static final Duration TOLERANCE = Duration.ofMinutes(5);
    private static final byte[] BODY = "{\"id\":\"evt_1\",\"type\":\"payment_intent.succeeded\"}"
        .getBytes(StandardCharsets.UTF_8);
    /**
     * printf '%s' '1767225600.{"id":"evt_1","type":"payment_intent.succeeded"}' \
     *   | openssl dgst -sha256 -hmac 'whsec_test_secret_0123456789abcdef' -hex
     */
    private static final String V1 = "37383d4fb3e2f3b508b71aeab7cb5f1fa72f631c195924680a261bca0a1136d1";
    /** The same with the secret 'whsec_rotated_secret_abcdef0123456789'. */
    private static final String V1_ROTATED = "127ff2da1753b3dd9ed8808dc696766cf386803633f40638b8f6608156b756b8";

    private final StripeSignature stripe = new StripeSignature();

    private InboundVerification verify(String header, byte[] body, List<String> secrets, Instant now) {
        InboundHeaders headers = header == null ? InboundHeaders.of(Map.of())
            : InboundHeaders.of(Map.of("Stripe-Signature", List.of(header)));
        return stripe.verify(headers, body, secrets, now, TOLERANCE);
    }

    private InboundVerification verify(String header) {
        return verify(header, BODY, List.of(SECRET), NOW);
    }

    @Test
    void acceptsTheFixedVector() {
        InboundVerification result = verify("t=" + T + ",v1=" + V1);
        assertThat(result.accepted()).isTrue();
        assertThat(result.reason()).isNull();
        assertThat(result.signedTime()).isEqualTo(NOW);
        assertThat(stripe.name()).isEqualTo("stripe");
    }

    @Test
    void signsAsStripeDoes() {
        assertThat(stripe.sign(SECRET, T, BODY)).containsExactly(Map.entry("Stripe-Signature", "t=" + T + ",v1=" + V1));
    }

    @Test
    void anyV1MatchingAnySecretPasses() {
        assertThat(verify("t=" + T + ",v1=" + "0".repeat(64) + ",v1=" + V1).accepted()).isTrue();
        // Rotating: the provider signs with both secrets, or with the new one only, while both are configured.
        assertThat(verify("t=" + T + ",v1=" + V1_ROTATED, BODY, List.of(SECRET, ROTATED), NOW).accepted()).isTrue();
        assertThat(verify("t=" + T + ",v1=" + V1 + ",v1=" + V1_ROTATED, BODY, List.of(ROTATED), NOW).accepted())
            .isTrue();
        assertThat(verify("t=" + T + ",v1=" + V1_ROTATED).reason()).isEqualTo(InboundVerification.Reason.NO_MATCH);
    }

    @Test
    void ignoresV0AndUnknownSchemesAndToleratesSpacesAndOrder() {
        assertThat(verify("v0=" + V1 + ", v1=" + V1.toUpperCase() + " , t=" + T + ",x9=whatever").accepted()).isTrue();
        // v0 alone is not enough.
        assertThat(verify("t=" + T + ",v0=" + V1).reason()).isEqualTo(InboundVerification.Reason.NO_MATCH);
    }

    @Test
    void refusesMissingDuplicateOrMalformedTimestamps() {
        assertThat(verify(null).reason()).isEqualTo(InboundVerification.Reason.MISSING_HEADER);
        assertThat(verify("v1=" + V1).reason()).isEqualTo(InboundVerification.Reason.MALFORMED);
        assertThat(verify("t=" + T + ",t=" + T + ",v1=" + V1).reason())
            .isEqualTo(InboundVerification.Reason.MALFORMED);
        assertThat(verify("t=12a,v1=" + V1).reason()).isEqualTo(InboundVerification.Reason.MALFORMED);
        assertThat(verify("t=-1,v1=" + V1).reason()).isEqualTo(InboundVerification.Reason.MALFORMED);
        assertThat(verify("t=,v1=" + V1).reason()).isEqualTo(InboundVerification.Reason.MALFORMED);
        assertThat(verify("t=99999999999999999999,v1=" + V1).reason()).isEqualTo(InboundVerification.Reason.MALFORMED);
        assertThat(verify("garbage").reason()).isEqualTo(InboundVerification.Reason.MALFORMED);
        assertThat(verify("t=" + T + ",v1=" + V1 + ",,").reason()).isEqualTo(InboundVerification.Reason.MALFORMED);
        // Two headers are as ambiguous as two timestamps.
        assertThat(stripe.verify(InboundHeaders.of(Map.of("stripe-signature", List.of("t=" + T + ",v1=" + V1,
            "t=" + T + ",v1=" + V1))), BODY, List.of(SECRET), NOW, TOLERANCE).reason())
            .isEqualTo(InboundVerification.Reason.MALFORMED);
        // Too many entries are refused before any HMAC is computed.
        assertThat(verify("t=" + T + (",v1=" + V1).repeat(StripeSignature.MAX_ENTRIES)).reason())
            .isEqualTo(InboundVerification.Reason.MALFORMED);
    }

    @Test
    void theToleranceBoundsBothDirections() {
        String header = "t=" + T + ",v1=" + V1;
        assertThat(verify(header, BODY, List.of(SECRET), NOW.plus(TOLERANCE)).accepted()).isTrue();
        assertThat(verify(header, BODY, List.of(SECRET), NOW.minus(TOLERANCE)).accepted()).isTrue();
        assertThat(verify(header, BODY, List.of(SECRET), NOW.plus(TOLERANCE).plusSeconds(1)).reason())
            .isEqualTo(InboundVerification.Reason.TOO_OLD);
        assertThat(verify(header, BODY, List.of(SECRET), NOW.minus(TOLERANCE).minusSeconds(1)).reason())
            .isEqualTo(InboundVerification.Reason.TOO_NEW);
        // The time of a forged signature tells nothing: the signature is checked first.
        assertThat(verify("t=" + T + ",v1=" + "0".repeat(64), BODY, List.of(SECRET), NOW.plus(Duration.ofDays(1)))
            .reason()).isEqualTo(InboundVerification.Reason.NO_MATCH);
    }

    @Test
    void aWrongSecretOrOneChangedByteFails() {
        assertThat(verify("t=" + T + ",v1=" + V1, BODY, List.of(SECRET + "x"), NOW).reason())
            .isEqualTo(InboundVerification.Reason.NO_MATCH);
        byte[] changed = BODY.clone();
        changed[5] ^= 1;
        assertThat(verify("t=" + T + ",v1=" + V1, changed, List.of(SECRET), NOW).reason())
            .isEqualTo(InboundVerification.Reason.NO_MATCH);
        assertThat(verify("t=" + (T + 1) + ",v1=" + V1).reason()).isEqualTo(InboundVerification.Reason.NO_MATCH);
        assertThat(verify("t=" + T + ",v1=" + V1, BODY, List.of(), NOW).reason())
            .isEqualTo(InboundVerification.Reason.NO_MATCH);
    }

    @Test
    void verificationResultsSayWhy() {
        assertThat(InboundVerification.accepted(NOW).accepted()).isTrue();
        assertThat(InboundVerification.rejected(InboundVerification.Reason.TOO_OLD).accepted()).isFalse();
        assertThatThrownBy(() -> new InboundVerification(true, InboundVerification.Reason.TOO_OLD, NOW))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InboundVerification(false, null, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void schemesAreFoundByName() {
        assertThat(InboundSchemes.of("stripe")).containsInstanceOf(StripeSignature.class);
        assertThat(InboundSchemes.of("hmac-sha256")).containsInstanceOf(HmacSha256Signature.class);
        assertThat(InboundSchemes.of("slack")).isEmpty();
        assertThat(InboundSchemes.of(null)).isEmpty();
        assertThat(InboundSchemes.names()).containsExactly("hmac-sha256", "stripe");
    }
}
