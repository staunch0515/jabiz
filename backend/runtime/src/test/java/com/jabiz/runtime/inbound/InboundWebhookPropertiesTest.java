package com.jabiz.runtime.inbound;

import com.jabiz.inbound.BodyHmacSignature;
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
        assertThat(provider.tolerance()).isNull();
        assertThat(provider.toleranceOrDefault()).isEqualTo(Duration.ofMinutes(5));
        assertThat(provider.rejectStatus()).isEqualTo(400);
        assertThat(provider.metadataHeaders()).isEmpty();
        assertThat(provider.bodyOnly()).isFalse();
        assertThat(provider.signatureScheme()).containsInstanceOf(com.jabiz.inbound.StripeSignature.class);
        assertThat(provider.maxBodySize()).isEqualTo(DataSize.ofMegabytes(1));
        assertThat(provider.rateLimit().perMinute()).isEqualTo(120);
        assertThat(provider.rateLimit().maxClients()).isEqualTo(100_000);
        assertThat(new InboundWebhookProperties(null).providers()).isEmpty();
    }

    @Test
    void aBodyOnlyProviderDefaultsToShopifysHeaders() {
        InboundWebhookProperties.Provider provider = new InboundWebhookProperties.Provider("body-hmac-sha256",
            List.of(InboundWebhookChecksTest.SECRET), List.of("orders/paid"), null, null, null, null, null, 401);
        assertThat(provider.bodyOnly()).isTrue();
        assertThat(provider.signedBodyOrDefault()).isEqualTo(new InboundWebhookProperties.SignedBody(
            "X-Shopify-Hmac-Sha256", "base64", "X-Shopify-Event-Id", "X-Shopify-Topic"));
        assertThat(provider.signatureScheme()).hasValueSatisfying(scheme -> {
            assertThat(scheme).isInstanceOf(BodyHmacSignature.class);
            assertThat(((BodyHmacSignature) scheme).header()).isEqualTo("X-Shopify-Hmac-Sha256");
            assertThat(scheme.signsTime()).isFalse();
        });
        InboundWebhookProperties.Provider hex = new InboundWebhookProperties.Provider("body-hmac-sha256",
            List.of(InboundWebhookChecksTest.SECRET), List.of("a"), null, null, null,
            new InboundWebhookProperties.SignedBody("X-Mac", "HEX", null, null), List.of("X-Account"), null);
        assertThat(((BodyHmacSignature) hex.signatureScheme().orElseThrow()).encoding())
            .isEqualTo(BodyHmacSignature.Encoding.HEX);
        assertThat(hex.signedBodyOrDefault().eventIdHeader()).isEqualTo("X-Shopify-Event-Id");
        assertThat(hex.toString()).doesNotContain(InboundWebhookChecksTest.SECRET).contains("metadataHeaders=[X-Account]");
    }

    @Test
    void providersBindFromProperties() {
        Map<String, String> source = new java.util.LinkedHashMap<>();
        source.put("jabiz.inbound-webhooks.providers.pay.scheme", "stripe");
        source.put("jabiz.inbound-webhooks.providers.pay.secrets[0]", InboundWebhookChecksTest.SECRET);
        source.put("jabiz.inbound-webhooks.providers.pay.event-types[0]", "payment_intent.succeeded");
        source.put("jabiz.inbound-webhooks.providers.pay.tolerance", "2m");
        source.put("jabiz.inbound-webhooks.providers.shop.scheme", "body-hmac-sha256");
        source.put("jabiz.inbound-webhooks.providers.shop.secrets[0]", InboundWebhookChecksTest.SECRET);
        source.put("jabiz.inbound-webhooks.providers.shop.event-types[0]", "orders/paid");
        source.put("jabiz.inbound-webhooks.providers.shop.reject-status", "401");
        source.put("jabiz.inbound-webhooks.providers.shop.metadata-headers[0]", "X-Shopify-Shop-Domain");
        source.put("jabiz.inbound-webhooks.providers.shop.signed-body.encoding", "hex");
        InboundWebhookProperties properties = new org.springframework.boot.context.properties.bind.Binder(
            new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(source))
            .bind("jabiz.inbound-webhooks", InboundWebhookProperties.class).get();
        assertThat(properties.providers().get("pay").tolerance()).isEqualTo(Duration.ofMinutes(2));
        assertThat(properties.providers().get("pay").secrets()).containsExactly(InboundWebhookChecksTest.SECRET);
        InboundWebhookProperties.Provider shop = properties.providers().get("shop");
        assertThat(shop.eventTypes()).containsExactly("orders/paid");
        assertThat(shop.rejectStatus()).isEqualTo(401);
        assertThat(shop.metadataHeaders()).containsExactly("X-Shopify-Shop-Domain");
        assertThat(shop.signedBodyOrDefault().encoding()).isEqualTo("hex");
        assertThat(shop.signedBodyOrDefault().signatureHeader()).isEqualTo("X-Shopify-Hmac-Sha256");
        assertThat(shop.tolerance()).isNull();
    }
}
