package com.jabiz.runtime.inbound;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Inbound webhooks ({@code jabiz.inbound-webhooks.*}; docs/design/11-ledger-events-jobs.md section 2.5, decision D37):
 * the providers whose signed requests {@code POST /api/inbound/{provider}} accepts. Configuration, not data: secrets
 * come from the environment through placeholders, like the platform's other keys. None by default, so every inbound
 * request is answered 404 until a provider is configured.
 *
 * @param providers by name ({@code [a-z][a-z0-9-]{0,39}}, the last segment of the path)
 */
@ConfigurationProperties("jabiz.inbound-webhooks")
public record InboundWebhookProperties(Map<String, Provider> providers) {

    public static final Duration DEFAULT_TOLERANCE = Duration.ofMinutes(5);
    public static final Duration MAX_TOLERANCE = Duration.ofHours(1);
    /** The largest body any provider accepts (decision D37 item 2); a provider may only lower it. */
    public static final DataSize MAX_BODY_SIZE = DataSize.ofMegabytes(1);

    public InboundWebhookProperties {
        providers = providers == null ? Map.of() : Map.copyOf(providers);
    }

    public Optional<Provider> find(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(providers.get(name));
    }

    @Override
    public String toString() {
        return "InboundWebhookProperties[providers=" + new TreeMap<>(providers) + "]";
    }

    /**
     * One sender of webhooks.
     *
     * @param scheme      the signature scheme: {@code stripe} or {@code hmac-sha256}
     * @param secrets     the signing secrets, at least 32 characters each, from the environment through placeholders
     *                    ({@code secrets: [${PAY_WEBHOOK_SECRET}]}); several while one is being replaced. Never
     *                    written anywhere
     * @param eventTypes  the provider's event types that are accepted (exact names, such as
     *                    {@code payment_intent.succeeded}); others are answered 200 and not stored
     * @param tolerance   how far the signed time may be from now, in either direction: default 5 minutes, at most 1 hour
     * @param maxBodySize the largest body accepted: default and at most 1 MB
     * @param rateLimit   requests per client address
     */
    public record Provider(String scheme, List<String> secrets, List<String> eventTypes, Duration tolerance,
        DataSize maxBodySize, RateLimit rateLimit) {

        public Provider {
            secrets = secrets == null ? List.of() : List.copyOf(secrets);
            eventTypes = eventTypes == null ? List.of() : List.copyOf(eventTypes);
            tolerance = tolerance == null ? DEFAULT_TOLERANCE : tolerance;
            maxBodySize = maxBodySize == null ? MAX_BODY_SIZE : maxBodySize;
            rateLimit = rateLimit == null ? new RateLimit(null, null) : rateLimit;
        }

        @Override
        public String toString() {
            return "Provider[scheme=" + scheme + ", secrets=[" + secrets.size() + " ***], eventTypes=" + eventTypes
                + ", tolerance=" + tolerance + ", maxBodySize=" + maxBodySize + ", rateLimit=" + rateLimit + "]";
        }
    }

    /**
     * @param perMinute  requests one client address may make per minute, default 120
     * @param maxClients client addresses tracked at most (least recently seen dropped first), bounding memory
     */
    public record RateLimit(Integer perMinute, Integer maxClients) {
        public RateLimit {
            perMinute = perMinute == null ? 120 : perMinute;
            maxClients = maxClients == null ? 100_000 : maxClients;
        }
    }
}
