package com.jabiz.inbound;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BodyHmacSignatureTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final String OTHER = "fedcba9876543210fedcba9876543210";
    private static final byte[] BODY = "{\"id\":820982911946154508,\"name\":\"#9999\"}".getBytes(StandardCharsets.UTF_8);
    /** Base64(HMAC-SHA256(SECRET, BODY)), computed apart from this code (Python's hmac). */
    private static final String BASE64 = "dU3rQcPRG/HDx7YiCQuFCjjwDzZ0wJcIv+RJ+40cH88=";
    private static final String HEX = "754deb41c3d11bf1c3c7b622090b850a38f00f3674c09708bfe449fb8d1c1fcf";
    private static final Instant NOW = Instant.parse("2026-10-26T00:00:00Z");

    private static InboundVerification verify(BodyHmacSignature scheme, Map<String, List<String>> headers,
        byte[] body, List<String> secrets) {
        return scheme.verify(InboundHeaders.of(headers), body, secrets, NOW, Duration.ofMinutes(5));
    }

    private static InboundVerification verify(Map<String, List<String>> headers) {
        return verify(BodyHmacSignature.DEFAULT, headers, BODY, List.of(SECRET));
    }

    private static Map<String, List<String>> signed(String value) {
        return Map.of("X-Shopify-Hmac-Sha256", List.of(value));
    }

    @Test
    void acceptsTheBase64HmacOfTheBodyWithoutASignedTime() {
        InboundVerification result = verify(signed(BASE64));
        assertThat(result.accepted()).isTrue();
        assertThat(result.signedTime()).isNull();
        assertThat(BodyHmacSignature.DEFAULT.name()).isEqualTo("body-hmac-sha256");
        assertThat(BodyHmacSignature.DEFAULT.signsTime()).isFalse();
        assertThat(InboundSchemes.of("body-hmac-sha256")).containsSame(BodyHmacSignature.DEFAULT);
        // Header names are not case sensitive.
        assertThat(verify(Map.of("x-shopify-hmac-sha256", List.of(BASE64))).accepted()).isTrue();
        // What it signs is what it verifies; the timestamp plays no part.
        assertThat(BodyHmacSignature.DEFAULT.sign(SECRET, 1L, BODY))
            .isEqualTo(BodyHmacSignature.DEFAULT.sign(SECRET, 2L, BODY))
            .containsExactlyEntriesOf(Map.of("X-Shopify-Hmac-Sha256", BASE64));
    }

    @Test
    void theTimeOfVerifyingPlaysNoPart() {
        // No time is signed, so no time can be too old: the receiver guards replays by the body (decision D57).
        assertThat(BodyHmacSignature.DEFAULT.verify(InboundHeaders.of(signed(BASE64)), BODY, List.of(SECRET),
            Instant.EPOCH, Duration.ZERO).accepted()).isTrue();
    }

    @Test
    void anotherHeaderAndHexMayBeConfigured() {
        BodyHmacSignature hex = BodyHmacSignature.of("X-Signature-Body", BodyHmacSignature.Encoding.HEX);
        assertThat(verify(hex, Map.of("X-Signature-Body", List.of(HEX)), BODY, List.of(SECRET)).accepted()).isTrue();
        assertThat(verify(hex, Map.of("X-Signature-Body", List.of(HEX.toUpperCase())), BODY, List.of(SECRET))
            .accepted()).isTrue();
        assertThat(hex.sign(SECRET, 0, BODY)).containsExactlyEntriesOf(Map.of("X-Signature-Body", HEX));
        // Base64 in a hex header is not cleanly hex.
        assertThat(verify(hex, Map.of("X-Signature-Body", List.of(BASE64)), BODY, List.of(SECRET)).reason())
            .isEqualTo(InboundVerification.Reason.MALFORMED);
        // The default header means nothing to it.
        assertThat(verify(hex, signed(BASE64), BODY, List.of(SECRET)).reason())
            .isEqualTo(InboundVerification.Reason.MISSING_HEADER);
        assertThat(BodyHmacSignature.of(null, null).header()).isEqualTo(BodyHmacSignature.DEFAULT_HEADER);
        assertThat(BodyHmacSignature.of(null, null).encoding()).isEqualTo(BodyHmacSignature.Encoding.BASE64);
        assertThat(hex).hasToString("BodyHmacSignature[header=X-Signature-Body, encoding=HEX]");
    }

    @Test
    void encodingsAreNamedInTheConfiguration() {
        assertThat(BodyHmacSignature.Encoding.of("base64")).isEqualTo(BodyHmacSignature.Encoding.BASE64);
        assertThat(BodyHmacSignature.Encoding.of("HEX")).isEqualTo(BodyHmacSignature.Encoding.HEX);
        assertThat(BodyHmacSignature.Encoding.of("base32")).isNull();
        assertThat(BodyHmacSignature.Encoding.of(null)).isNull();
        assertThatThrownBy(() -> BodyHmacSignature.of("X Bad: header", BodyHmacSignature.Encoding.HEX))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anyConfiguredSecretMayHaveSigned() {
        String byOther = BodyHmacSignature.DEFAULT.sign(OTHER, 0, BODY).get("X-Shopify-Hmac-Sha256");
        assertThat(verify(BodyHmacSignature.DEFAULT, signed(byOther), BODY, List.of(SECRET, OTHER)).accepted())
            .isTrue();
        assertThat(verify(BodyHmacSignature.DEFAULT, signed(byOther), BODY, List.of(SECRET)).reason())
            .isEqualTo(InboundVerification.Reason.NO_MATCH);
        assertThat(verify(BodyHmacSignature.DEFAULT, signed(BASE64), BODY, List.of()).reason())
            .isEqualTo(InboundVerification.Reason.NO_MATCH);
    }

    @Test
    void aMissingRepeatedOrMalformedSignatureIsRefused() {
        assertThat(verify(Map.of()).reason()).isEqualTo(InboundVerification.Reason.MISSING_HEADER);
        assertThat(verify(Map.of("X-Shopify-Hmac-Sha256", List.of(BASE64, BASE64))).reason())
            .isEqualTo(InboundVerification.Reason.MALFORMED);
        assertThat(verify(signed("not base64!")).reason()).isEqualTo(InboundVerification.Reason.MALFORMED);
        // Clean Base64 of the wrong length: not an HMAC-SHA256.
        assertThat(verify(signed("AAAA")).reason()).isEqualTo(InboundVerification.Reason.MALFORMED);
        assertThat(verify(signed("")).reason()).isEqualTo(InboundVerification.Reason.MALFORMED);
        // One signature has one spelling: not without its padding, not with other bits after the last byte.
        assertThat(verify(signed(BASE64.substring(0, BASE64.length() - 1))).reason())
            .isEqualTo(InboundVerification.Reason.MALFORMED);
        assertThat(verify(signed(BASE64.replace("88=", "89="))).reason())
            .isEqualTo(InboundVerification.Reason.MALFORMED);
        // Of the right length, but another body's.
        String another = BodyHmacSignature.DEFAULT.sign(SECRET, 0, "{}".getBytes(StandardCharsets.UTF_8))
            .get("X-Shopify-Hmac-Sha256");
        assertThat(verify(signed(another)).reason()).isEqualTo(InboundVerification.Reason.NO_MATCH);
    }

    @Test
    void oneChangedByteOfTheBodyFails() {
        byte[] changed = BODY.clone();
        changed[changed.length - 2] ^= 1;
        assertThat(verify(BodyHmacSignature.DEFAULT, signed(BASE64), changed, List.of(SECRET)).reason())
            .isEqualTo(InboundVerification.Reason.NO_MATCH);
    }

    @Test
    void anAcceptedVerificationMayLackItsTimeOnlyWhenSaidSo() {
        assertThat(InboundVerification.withoutSignedTime().accepted()).isTrue();
        assertThatThrownBy(() -> InboundVerification.accepted(null)).isInstanceOf(NullPointerException.class);
    }
}
