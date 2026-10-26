package com.jabiz.runtime.inbound;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InboundWebhookChecksTest {

    static final String SECRET = "whsec_0123456789abcdef0123456789abcdef";

    static InboundWebhookProperties.Provider provider(String scheme, List<String> secrets, List<String> types) {
        return new InboundWebhookProperties.Provider(scheme, secrets, types, null, null, null);
    }

    private static List<String> problems(Map<String, InboundWebhookProperties.Provider> providers) {
        return new InboundWebhookChecks(new InboundWebhookProperties(providers)).check().stream()
            .map(p -> p.category() + " " + p.location() + ": " + p.message()).toList();
    }

    @Test
    void validProvidersPass() {
        assertThat(problems(Map.of(
            "demo-pay", provider("stripe", List.of(SECRET), List.of("payment_intent.succeeded")),
            "peer", new InboundWebhookProperties.Provider("hmac-sha256", List.of(SECRET, SECRET + "2"),
                List.of("finance.invoice-posted"), Duration.ofHours(1), DataSize.ofKilobytes(64),
                new InboundWebhookProperties.RateLimit(10, 100))))).isEmpty();
        assertThat(problems(Map.of())).isEmpty();
    }

    @Test
    void everyProblemIsReportedAtOnce() {
        Map<String, InboundWebhookProperties.Provider> providers = new LinkedHashMap<>();
        providers.put("Bad_Name", provider("stripe", List.of(SECRET), List.of("a")));
        providers.put("unknown-scheme", provider("slack", List.of(SECRET), List.of("a")));
        providers.put("no-scheme", provider(null, List.of(SECRET), List.of("a")));
        providers.put("no-secret", provider("stripe", null, List.of("a")));
        providers.put("short-secret", provider("stripe", List.of(SECRET, "short"), List.of("a")));
        providers.put("no-types", provider("stripe", List.of(SECRET), List.of()));
        providers.put("bad-types", provider("stripe", List.of(SECRET), List.of("ok.type", "not valid!",
            "x".repeat(90))));
        providers.put("bounds", new InboundWebhookProperties.Provider("stripe", List.of(SECRET), List.of("a"),
            Duration.ofHours(2), DataSize.ofMegabytes(2), new InboundWebhookProperties.RateLimit(0, -1)));
        providers.put("zero", new InboundWebhookProperties.Provider("stripe", List.of(SECRET), List.of("a"),
            Duration.ZERO, DataSize.ofBytes(0), null));
        assertThat(problems(providers)).containsExactlyInAnyOrder(
            "INBOUND jabiz.inbound-webhooks.providers.Bad_Name: not a valid provider name (lower-case letters, "
                + "digits and '-', at most 40, starting with a letter)",
            "INBOUND jabiz.inbound-webhooks.providers.unknown-scheme.scheme: unknown signature scheme 'slack' "
                + "(known: [body-hmac-sha256, hmac-sha256, stripe])",
            "INBOUND jabiz.inbound-webhooks.providers.no-scheme.scheme: unknown signature scheme 'null' "
                + "(known: [body-hmac-sha256, hmac-sha256, stripe])",
            "INBOUND jabiz.inbound-webhooks.providers.no-secret.secrets: at least one secret is required",
            "INBOUND jabiz.inbound-webhooks.providers.short-secret.secrets[1]: missing or shorter than 32 characters",
            "INBOUND jabiz.inbound-webhooks.providers.no-types.event-types: no event types: every request would be "
                + "ignored",
            "INBOUND jabiz.inbound-webhooks.providers.bad-types.event-types[1]: jabiz.inbound.bad-types.not valid! "
                + "is not a valid event type (letters, digits, '.', '_', ':', '-', and '/' for '.'; at most 100 characters)",
            "INBOUND jabiz.inbound-webhooks.providers.bad-types.event-types[2]: jabiz.inbound.bad-types."
                + "x".repeat(90) + " is not a valid event type (letters, digits, '.', '_', ':', '-', and '/' for '.'; at "
                + "most 100 characters)",
            "INBOUND jabiz.inbound-webhooks.providers.bounds.tolerance: must be more than 0 and at most PT1H",
            "INBOUND jabiz.inbound-webhooks.providers.bounds.max-body-size: must be more than 0 and at most 1MB",
            "INBOUND jabiz.inbound-webhooks.providers.bounds.rate-limit.per-minute: must be positive",
            "INBOUND jabiz.inbound-webhooks.providers.bounds.rate-limit.max-clients: must be positive",
            "INBOUND jabiz.inbound-webhooks.providers.zero.tolerance: must be more than 0 and at most PT1H",
            "INBOUND jabiz.inbound-webhooks.providers.zero.max-body-size: must be more than 0 and at most 1MB");
    }

    @Test
    void nullOrBlankEntriesAreReportedNotThrown() {
        Map<String, InboundWebhookProperties.Provider> providers = new LinkedHashMap<>();
        providers.put("holes", provider("stripe", java.util.Arrays.asList(SECRET, null),
            java.util.Arrays.asList("a", null, " ")));
        assertThat(problems(providers)).containsExactlyInAnyOrder(
            "INBOUND jabiz.inbound-webhooks.providers.holes.secrets[1]: missing or shorter than 32 characters",
            "INBOUND jabiz.inbound-webhooks.providers.holes.event-types[1]: missing or blank",
            "INBOUND jabiz.inbound-webhooks.providers.holes.event-types[2]: missing or blank");
    }

    static InboundWebhookProperties.Provider bodyOnly(Duration tolerance, InboundWebhookProperties.SignedBody signed,
        List<String> metadata, Integer rejectStatus, List<String> types) {
        return new InboundWebhookProperties.Provider("body-hmac-sha256", List.of(SECRET), types, tolerance, null, null,
            signed, metadata, rejectStatus);
    }

    @Test
    void aBodyOnlyProviderWithItsDefaultsPasses() {
        assertThat(problems(Map.of("shop", bodyOnly(null, null, List.of("X-Shopify-Shop-Domain"), 401,
            List.of("orders/paid", "app/uninstalled", "customers/data_request"))))).isEmpty();
        assertThat(problems(Map.of("other", bodyOnly(null, new InboundWebhookProperties.SignedBody("X-Body-Mac",
            "hex", "X-Event", "X-Kind"), List.of(), 400, List.of("a"))))).isEmpty();
    }

    @Test
    void aBodyOnlyProviderIsCheckedForWhatItCannotKeep() {
        Map<String, InboundWebhookProperties.Provider> providers = new LinkedHashMap<>();
        providers.put("timed", bodyOnly(Duration.ofMinutes(5), null, null, null, List.of("a")));
        providers.put("encoding", bodyOnly(null, new InboundWebhookProperties.SignedBody(null, "base32", null, null),
            null, null, List.of("a")));
        providers.put("names", bodyOnly(null, new InboundWebhookProperties.SignedBody("X Bad", null, "x-shopify-topic",
            null), null, null, List.of("a")));
        providers.put("status", bodyOnly(null, null, null, 403, List.of("a")));
        providers.put("clash", bodyOnly(null, null, null, null, List.of("orders/paid", "orders.paid")));
        providers.put("signed-elsewhere", new InboundWebhookProperties.Provider("stripe", List.of(SECRET),
            List.of("a"), null, null, null, new InboundWebhookProperties.SignedBody(null, null, null, null), null,
            null));
        assertThat(problems(providers)).containsExactlyInAnyOrder(
            "INBOUND jabiz.inbound-webhooks.providers.timed.tolerance: the scheme body-hmac-sha256 signs no time: a "
                + "tolerance cannot apply (remove it)",
            "INBOUND jabiz.inbound-webhooks.providers.encoding.signed-body.encoding: must be base64 or hex",
            "INBOUND jabiz.inbound-webhooks.providers.names.signed-body.signature-header: not a header name "
                + "(letters, digits and '-', at most 64)",
            "INBOUND jabiz.inbound-webhooks.providers.names.signed-body.event-type-header: X-Shopify-Topic is named "
                + "twice",
            "INBOUND jabiz.inbound-webhooks.providers.status.reject-status: must be 400 or 401",
            "INBOUND jabiz.inbound-webhooks.providers.clash.event-types[1]: orders.paid and orders/paid are both the "
                + "event type jabiz.inbound.clash.orders.paid",
            "INBOUND jabiz.inbound-webhooks.providers.signed-elsewhere.signed-body: only for the scheme "
                + "body-hmac-sha256");
        // A provider whose settings are not valid has no scheme, so it is never served.
        assertThat(providers.get("encoding").signatureScheme()).isEmpty();
        assertThat(providers.get("names").signatureScheme()).isEmpty();
    }

    @Test
    void metadataHeadersAreFewNamedAndNeverCredentials() {
        Map<String, InboundWebhookProperties.Provider> providers = new LinkedHashMap<>();
        providers.put("many", bodyOnly(null, null, List.of("A1", "A2", "A3", "A4", "A5", "A6", "A7", "A8", "A9"), null,
            List.of("a")));
        providers.put("bad", bodyOnly(null, null, java.util.Arrays.asList("X-Shopify-Hmac-Sha256", "Authorization",
            "Cookie", "X Bad", null, "X-Shop", "x-shop"), null, List.of("a")));
        providers.put("credentials", bodyOnly(null, new InboundWebhookProperties.SignedBody("X-Body-Mac", null, null,
            null), List.of("X-Gitlab-Token", "X-Api-Key", "X-Client-Secret", "x-body-mac"), null, List.of("a")));
        // Any scheme may keep metadata headers.
        providers.put("stripe-meta", new InboundWebhookProperties.Provider("stripe", List.of(SECRET), List.of("a"),
            null, null, null, null, List.of("Stripe-Account"), null));
        String at = "INBOUND jabiz.inbound-webhooks.providers.";
        assertThat(problems(providers)).containsExactlyInAnyOrder(
            at + "many.metadata-headers: at most 8 headers",
            at + "bad.metadata-headers[0]: X-Shopify-Hmac-Sha256 may hold a signature or a credential: never kept",
            at + "bad.metadata-headers[1]: Authorization may hold a signature or a credential: never kept",
            at + "bad.metadata-headers[2]: Cookie may hold a signature or a credential: never kept",
            at + "bad.metadata-headers[3]: not a header name (letters, digits and '-', at most 64)",
            at + "bad.metadata-headers[4]: not a header name (letters, digits and '-', at most 64)",
            at + "bad.metadata-headers[6]: x-shop is named twice",
            at + "credentials.metadata-headers[0]: X-Gitlab-Token may hold a signature or a credential: never kept",
            at + "credentials.metadata-headers[1]: X-Api-Key may hold a signature or a credential: never kept",
            at + "credentials.metadata-headers[2]: X-Client-Secret may hold a signature or a credential: never kept",
            at + "credentials.metadata-headers[3]: x-body-mac may hold a signature or a credential: never kept");
    }

    @Test
    void onlyValidProvidersAreServed() {
        InboundWebhookProperties properties = new InboundWebhookProperties(Map.of(
            "good", provider("stripe", List.of(SECRET), List.of("a")),
            "bad", provider("stripe", List.of("short"), List.of("a"))));
        InboundWebhookChecks checks = new InboundWebhookChecks(properties);
        assertThat(checks.served("good")).isPresent();
        assertThat(checks.served("bad")).isEmpty();
        assertThat(checks.served("missing")).isEmpty();
        assertThat(checks.served(null)).isEmpty();
    }
}
