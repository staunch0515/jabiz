package com.jabiz.runtime.inbound;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InboundWebhookPropertiesTest {

    @Test
    void secretsNeverAppearInText() {
        InboundWebhookProperties.Provider provider = InboundWebhookChecksTest.provider("stripe",
            List.of(InboundWebhookChecksTest.SECRET, "whsec_second_secret_value_0123456789"), List.of("a"));
        InboundWebhookProperties properties = new InboundWebhookProperties(Map.of("demo-pay", provider));
        assertThat(provider.toString()).doesNotContain(InboundWebhookChecksTest.SECRET, "second_secret")
            .contains("secrets=[2 ***]", "scheme=stripe");
        assertThat(properties.toString()).doesNotContain(InboundWebhookChecksTest.SECRET, "second_secret")
            .contains("demo-pay");
    }

    @Test
    void defaultsAreTheSafeOnes() {
        InboundWebhookProperties.Provider provider = new InboundWebhookProperties.Provider("stripe", null, null, null,
            null, null);
        assertThat(provider.secrets()).isEmpty();
        assertThat(provider.eventTypes()).isEmpty();
        assertThat(provider.tolerance()).isEqualTo(Duration.ofMinutes(5));
        assertThat(provider.maxBodySize()).isEqualTo(DataSize.ofMegabytes(1));
        assertThat(provider.rateLimit().perMinute()).isEqualTo(120);
        assertThat(provider.rateLimit().maxClients()).isEqualTo(100_000);
        assertThat(new InboundWebhookProperties(null).providers()).isEmpty();
    }
}
