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
                + "(known: [hmac-sha256, stripe])",
            "INBOUND jabiz.inbound-webhooks.providers.no-scheme.scheme: unknown signature scheme 'null' "
                + "(known: [hmac-sha256, stripe])",
            "INBOUND jabiz.inbound-webhooks.providers.no-secret.secrets: at least one secret is required",
            "INBOUND jabiz.inbound-webhooks.providers.short-secret.secrets[1]: missing or shorter than 32 characters",
            "INBOUND jabiz.inbound-webhooks.providers.no-types.event-types: no event types: every request would be "
                + "ignored",
            "INBOUND jabiz.inbound-webhooks.providers.bad-types.event-types[1]: jabiz.inbound.bad-types.not valid! "
                + "is not a valid event type (letters, digits, '.', '_', ':', '-'; at most 100 characters)",
            "INBOUND jabiz.inbound-webhooks.providers.bad-types.event-types[2]: jabiz.inbound.bad-types."
                + "x".repeat(90) + " is not a valid event type (letters, digits, '.', '_', ':', '-'; at most 100 "
                + "characters)",
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
