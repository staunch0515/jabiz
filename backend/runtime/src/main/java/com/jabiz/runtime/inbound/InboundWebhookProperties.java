package com.jabiz.runtime.inbound;

import com.jabiz.inbound.BodyHmacSignature;
import com.jabiz.inbound.InboundSchemes;
import com.jabiz.inbound.InboundSignatureScheme;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
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
     * @param scheme          the signature scheme: {@code stripe}, {@code hmac-sha256} or {@code body-hmac-sha256}
     * @param secrets         the signing secrets, at least 32 characters each, from the environment through
     *                        placeholders ({@code secrets: [${PAY_WEBHOOK_SECRET}]}); several while one is being
     *                        replaced. Never written anywhere
     * @param eventTypes      the provider's event types that are accepted (exact names, such as
     *                        {@code payment_intent.succeeded}); others are answered 200 and not stored
     * @param tolerance       how far the signed time may be from now, in either direction: default 5 minutes, at most
     *                        1 hour; not to be given for a scheme that signs no time (decision D57), null when not given
     * @param maxBodySize     the largest body accepted: default and at most 1 MB
     * @param rateLimit       requests per client address
     * @param signedBody      for {@code body-hmac-sha256} only: its signature header and encoding and the headers that
     *                        name the event (decision D57)
     * @param metadataHeaders request headers kept with the event as they came, at most {@value #MAX_METADATA_HEADERS}
     *                        (decision D57): not covered by any signature
     * @param rejectStatus    the status of a request whose signature fails: 400 (default) or 401 (decision D57)
     */
    public record Provider(String scheme, List<String> secrets, List<String> eventTypes, Duration tolerance,
        DataSize maxBodySize, RateLimit rateLimit, SignedBody signedBody, List<String> metadataHeaders,
        Integer rejectStatus) {

        public static final int MAX_METADATA_HEADERS = 8;

        // Bound through this constructor: the record has another one, so the binder must be told.
        @ConstructorBinding
        public Provider {
            // Null entries are kept for the startup check to report, rather than failing the binding.
            secrets = secrets == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(secrets));
            eventTypes = eventTypes == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(eventTypes));
            maxBodySize = maxBodySize == null ? MAX_BODY_SIZE : maxBodySize;
            rateLimit = rateLimit == null ? new RateLimit(null, null) : rateLimit;
            metadataHeaders = metadataHeaders == null ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(metadataHeaders));
            rejectStatus = rejectStatus == null ? 400 : rejectStatus;
        }

        /** A provider of a scheme that signs a time, as before decision D57. */
        public Provider(String scheme, List<String> secrets, List<String> eventTypes, Duration tolerance,
            DataSize maxBodySize, RateLimit rateLimit) {
            this(scheme, secrets, eventTypes, tolerance, maxBodySize, rateLimit, null, null, null);
        }

        /** The tolerance in force: the configured one, else {@link #DEFAULT_TOLERANCE}. */
        public Duration toleranceOrDefault() {
            return tolerance == null ? DEFAULT_TOLERANCE : tolerance;
        }

        /** The signed-body settings in force: the configured ones, else the defaults (Shopify's headers). */
        public SignedBody signedBodyOrDefault() {
            return signedBody == null ? new SignedBody(null, null, null, null) : signedBody;
        }

        /** Whether the scheme is the body-only one of decision D57. */
        public boolean bodyOnly() {
            return BodyHmacSignature.NAME.equals(scheme);
        }

        /** The scheme as configured; empty when the name is unknown or its settings are not valid. */
        public Optional<InboundSignatureScheme> signatureScheme() {
            if (!bodyOnly()) {
                return InboundSchemes.of(scheme);
            }
            SignedBody settings = signedBodyOrDefault();
            BodyHmacSignature.Encoding encoding = BodyHmacSignature.Encoding.of(settings.encoding());
            if (encoding == null || !BodyHmacSignature.HEADER.matcher(settings.signatureHeader()).matches()) {
                return Optional.empty();
            }
            return Optional.of(BodyHmacSignature.of(settings.signatureHeader(), encoding));
        }

        @Override
        public String toString() {
            return "Provider[scheme=" + scheme + ", secrets=[" + secrets.size() + " ***], eventTypes=" + eventTypes
                + ", tolerance=" + tolerance + ", maxBodySize=" + maxBodySize + ", rateLimit=" + rateLimit
                + ", signedBody=" + signedBody + ", metadataHeaders=" + metadataHeaders + ", rejectStatus="
                + rejectStatus + "]";
        }
    }

    /**
     * How a body-only signature is given and where the event is named (decision D57). The defaults are Shopify's.
     *
     * @param signatureHeader the header of the signature, default {@code X-Shopify-Hmac-Sha256}
     * @param encoding        {@code base64} (default) or {@code hex}
     * @param eventIdHeader   the header naming the event, the same for every delivery of it, default
     *                        {@code X-Shopify-Event-Id}: not signed, for duplicates only
     * @param eventTypeHeader the header naming its type, default {@code X-Shopify-Topic}: not signed, for routing only
     */
    public record SignedBody(String signatureHeader, String encoding, String eventIdHeader, String eventTypeHeader) {

        public static final String DEFAULT_EVENT_ID_HEADER = "X-Shopify-Event-Id";
        public static final String DEFAULT_EVENT_TYPE_HEADER = "X-Shopify-Topic";

        public SignedBody {
            signatureHeader = signatureHeader == null ? BodyHmacSignature.DEFAULT_HEADER : signatureHeader;
            encoding = encoding == null ? "base64" : encoding;
            eventIdHeader = eventIdHeader == null ? DEFAULT_EVENT_ID_HEADER : eventIdHeader;
            eventTypeHeader = eventTypeHeader == null ? DEFAULT_EVENT_TYPE_HEADER : eventTypeHeader;
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
