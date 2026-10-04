package com.jabiz.webhook;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookSignatureTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final byte[] BODY = "{\"eventType\":\"x\"}".getBytes(StandardCharsets.UTF_8);

    @Test
    void signsTheTimestampAndTheBody() {
        // openssl: printf '1700000000.{"eventType":"x"}' | openssl dgst -sha256 -hmac 0123456789abcdef0123456789abcdef
        assertThat(WebhookSignature.sign(SECRET, 1_700_000_000L, BODY))
            .isEqualTo("v1=" + EXPECTED);
    }

    @Test
    void matchesOnlyTheSameTimestampBodyAndSecret() {
        String signature = WebhookSignature.sign(SECRET, 1_700_000_000L, BODY);
        assertThat(WebhookSignature.matches(SECRET, 1_700_000_000L, BODY, signature)).isTrue();
        assertThat(WebhookSignature.matches(SECRET, 1_700_000_001L, BODY, signature)).isFalse();
        assertThat(WebhookSignature.matches(SECRET, 1_700_000_000L, "{}".getBytes(StandardCharsets.UTF_8), signature))
            .isFalse();
        assertThat(WebhookSignature.matches(SECRET.replace('0', '1'), 1_700_000_000L, BODY, signature)).isFalse();
        assertThat(WebhookSignature.matches(SECRET, 1_700_000_000L, BODY, null)).isFalse();
    }

    private static final String EXPECTED = "1e7b3ddc1037c3ae2f079136e9cb9f29af21c14ae955f475f536eb9bd8333a01";
}
