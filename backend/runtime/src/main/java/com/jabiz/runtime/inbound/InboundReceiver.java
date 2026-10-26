package com.jabiz.runtime.inbound;

import com.jabiz.context.RequestContext;
import com.jabiz.inbound.InboundHeaders;
import com.jabiz.inbound.InboundSignatureScheme;
import com.jabiz.inbound.InboundVerification;
import com.jabiz.query.BoundValue;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.observability.PlatformObservations;
import com.jabiz.runtime.process.ExecutionOptions;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.runtime.storage.UniqueKeyViolationException;
import io.micrometer.common.KeyValues;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Receives one inbound webhook request (docs/design/11-ledger-events-jobs.md section 2.5; decision D37), for the HTTP
 * endpoint and for scenario replay alike:
 * <ol>
 *   <li>the provider must be configured without problems, or it is not found (404);</li>
 *   <li>the body, at most the provider's size, is verified by the provider's scheme against its secrets, at the time
 *       of the injected clock;</li>
 *   <li>only then is it read: strict UTF-8, one JSON object, naming its event ({@link InboundEventExtractor});</li>
 *   <li>an event type outside the provider's list is {@link Outcome#IGNORED}, not stored; an event stored before (its
 *       id within its type, and for a body-only scheme also the same body of the type, decision D57) is a
 *       {@link Outcome#DUPLICATE};</li>
 *   <li>otherwise {@code INBOUND_RECEIVE} runs as the system and stores it with its outbox event in one transaction
 *       ({@link Outcome#ACCEPTED}); a concurrent arrival of the same event waits on the unique key, then fails and is
 *       a duplicate too.</li>
 * </ol>
 * Any failure of steps 2 and 3 is an {@link InboundRejectedException} (400, or the provider's {@code reject-status}
 * for a failed signature, decision D57; the same answer whatever went wrong); its
 * reason is logged and tagged, never stored. Nothing of the body, the signature or the secrets is logged or tagged:
 * the observation {@code jabiz.inbound.receive} carries the provider, the result ({@code ACCEPTED}, {@code DUPLICATE},
 * {@code IGNORED}, {@code REJECTED}, or {@code FAILED} for an unexpected error) and the reason ({@code none}, the
 * rejection's, or {@code error}) only. The
 * application's processes run later, from the outbox, never in the request.
 */
@Component
public class InboundReceiver {

    private static final Logger log = LoggerFactory.getLogger(InboundReceiver.class);

    static final String REASON = "reason";
    private static final String NO_REASON = "none";
    static final String REJECTED = "REJECTED";
    static final String FAILED = "FAILED";
    private static final String ERROR = "error";
    private static final int MAX_CONTENT_TYPE = 100;

    /** What became of an accepted request; each is answered 200. */
    public enum Outcome {
        /** Stored and published. */
        ACCEPTED,
        /** Stored before: nothing changed. */
        DUPLICATE,
        /** A type the provider's list does not name: not stored. */
        IGNORED
    }

    private final InboundWebhookChecks providers;
    private final ProcessExecutor executor;
    private final StorageAdapterRegistry storages;
    private final String poolRef;
    private final Clock clock;
    private final PlatformObservations observations;

    InboundReceiver(InboundWebhookChecks providers, ProcessExecutor executor, StorageAdapterRegistry storages,
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef, Clock clock,
        PlatformObservations observations) {
        this.providers = providers;
        this.executor = executor;
        this.storages = storages;
        this.poolRef = poolRef;
        this.clock = clock;
        this.observations = observations;
    }

    /** The largest body the provider accepts, in bytes; 404 when it is not served. */
    public long maxBodySize(String provider) {
        return served(provider).maxBodySize().toBytes();
    }

    /** Receives a body read already (scenario replay, tests). */
    public Mono<Outcome> receive(String provider, InboundHeaders headers, String contentType, byte[] body) {
        Objects.requireNonNull(body, "body must not be null");
        return receive(provider, headers, contentType, Mono.just(body));
    }

    /**
     * Receives a request whose body {@code body} reads; it may fail with an {@link InboundRejectedException} of its
     * own (a body larger than {@link #maxBodySize}).
     */
    public Mono<Outcome> receive(String provider, InboundHeaders headers, String contentType, Mono<byte[]> body) {
        return Mono.defer(() -> {
            InboundWebhookProperties.Provider configuration = served(provider);
            Mono<Outcome> work = body.defaultIfEmpty(new byte[0])
                .flatMap(bytes -> receive(provider, configuration, headers, contentType, bytes))
                // Results are named as the outbox delivery names its own (CONSUMED, DUPLICATE, FAILED).
                .flatMap(outcome -> PlatformObservations.tag(PlatformObservations.RESULT, outcome.name())
                    .then(PlatformObservations.tag(REASON, NO_REASON))
                    .thenReturn(outcome))
                .onErrorResume(error -> {
                    if (error instanceof InboundRejectedException rejected) {
                        log.info("Inbound webhook request of {} rejected: {}", provider, rejected.reason());
                        return PlatformObservations.tag(PlatformObservations.RESULT, REJECTED)
                            .then(PlatformObservations.tag(REASON, rejected.reason()))
                            .then(Mono.error(rejected));
                    }
                    // Not the sender's doing (the database, a defect): the provider will deliver it again.
                    return PlatformObservations.tag(PlatformObservations.RESULT, FAILED)
                        .then(PlatformObservations.tag(REASON, ERROR))
                        .then(Mono.error(error));
                });
            return observations.mono(PlatformObservations.INBOUND, "inbound " + provider,
                KeyValues.of("provider", provider), work);
        });
    }

    private InboundWebhookProperties.Provider served(String provider) {
        return providers.served(provider).orElseThrow(() -> new EntityNotFoundException(
            "No inbound webhook provider " + provider));
    }

    private Mono<Outcome> receive(String provider, InboundWebhookProperties.Provider configuration,
        InboundHeaders headers, String contentType, byte[] body) {
        if (body.length > configuration.maxBodySize().toBytes()) {
            return Mono.error(new InboundRejectedException("too_large"));
        }
        InboundSignatureScheme scheme = configuration.signatureScheme().orElseThrow();
        InboundVerification verification = scheme.verify(headers, body, configuration.secrets(), clock.instant(),
            configuration.toleranceOrDefault());
        if (!verification.accepted()) {
            return Mono.error(new InboundRejectedException(verification.reason().name().toLowerCase(Locale.ROOT),
                SIGNATURE_FAILURES.contains(verification.reason()) ? configuration.rejectStatus() : 400));
        }
        if (scheme.signsTime() && verification.signedTime() == null) {
            // A scheme that signs a time must say which, or its tolerance guarded nothing.
            return Mono.error(new IllegalStateException("Scheme " + scheme.name() + " accepted without a signed time"));
        }
        InboundEventExtractor.Extracted event = configuration.bodyOnly()
            ? InboundEventExtractor.extractFromHeaders(configuration.signedBodyOrDefault(), headers, body)
            : InboundEventExtractor.extract(scheme.name(), headers, body);
        if (!configuration.eventTypes().contains(event.eventType())) {
            log.debug("Inbound webhook event of {} ignored: its type is not accepted", provider);
            return Mono.just(Outcome.IGNORED);
        }
        Map<String, String> metadata = metadata(configuration.metadataHeaders(), headers);
        // Decision D57: where the event's id is not signed, a request captured once could come again with another id;
        // the same body of the same type is then a duplicate too.
        boolean replayGuard = configuration.bodyOnly();
        InboundProcesses.ReceiveInput input = new InboundProcesses.ReceiveInput(provider, event.externalEventId(),
            event.eventType(), event.livemode(), verification.signedTime(), contentType(contentType), event.text(),
            metadata, replayGuard);
        return stored(provider, event.eventType(), event.externalEventId(),
                replayGuard ? RecordInboundEvent.sha256(event.text()) : null)
            .flatMap(found -> found ? Mono.just(Outcome.DUPLICATE) : record(input));
    }

    /** The failures of the signature itself, answered with the provider's status (decision D57); the rest 400. */
    private static final Set<InboundVerification.Reason> SIGNATURE_FAILURES = Set.of(
        InboundVerification.Reason.MISSING_HEADER, InboundVerification.Reason.MALFORMED,
        InboundVerification.Reason.NO_MATCH);

    /** The largest value of a metadata header kept (decision D57). */
    static final int MAX_METADATA_VALUE = 255;
    private static final Pattern METADATA_VALUE = Pattern.compile(
        "[\\x20-\\x7E]{1," + MAX_METADATA_VALUE + "}");

    /**
     * The configured metadata headers of the request (decision D57), by their configured names: each at most once, of
     * printable ASCII, at most {@value #MAX_METADATA_VALUE} characters; an absent one is left out. Not signed.
     */
    static Map<String, String> metadata(List<String> names, InboundHeaders headers) {
        Map<String, String> kept = new LinkedHashMap<>();
        for (String name : names) {
            List<String> values = headers.values(name);
            if (values.isEmpty()) {
                continue;
            }
            String value = values.size() == 1 && values.getFirst() != null ? values.getFirst().trim() : null;
            if (value == null || !METADATA_VALUE.matcher(value).matches()) {
                throw new InboundRejectedException("metadata_header");
            }
            kept.put(name, value);
        }
        return kept;
    }

    /**
     * Whether the event was stored before: a repeated delivery then costs no operation and no sequence number. An event
     * is the provider's id of it within its type (decision D57: one action of a sender may give several types the
     * same id); with {@code bodySha256}, the same body of the same type also counts.
     */
    private Mono<Boolean> stored(String provider, String eventType, String externalEventId, String bodySha256) {
        Map<String, BoundValue> params = new LinkedHashMap<>();
        params.put("provider", BoundValue.of(provider));
        params.put("type", BoundValue.of(eventType));
        params.put("id", BoundValue.of(externalEventId));
        String sql = "SELECT 1 AS found FROM " + InboundEntities.TABLE
            + " WHERE provider = :provider AND event_type = :type AND external_event_id = :id";
        if (bodySha256 != null) {
            params.put("sha", BoundValue.of(bodySha256));
            sql += " UNION ALL SELECT 1 AS found FROM " + InboundEntities.TABLE
                + " WHERE provider = :provider AND event_type = :type AND body_sha256 = :sha AND replay_guard";
        }
        return storages.getEngine(poolRef).select(sql, params).hasElements();
    }

    private Mono<Outcome> record(InboundProcesses.ReceiveInput input) {
        return Mono.deferContextual(view -> {
            String requestId = view.<RequestContext>getOrEmpty(RequestContext.class).map(RequestContext::requestId)
                .orElse("inbound");
            RequestContext system = RequestContext.system(Locale.ENGLISH, requestId);
            return executor.run(InboundProcesses.RECEIVE_PROCESS, input, ExecutionOptions.NONE)
                .thenReturn(Outcome.ACCEPTED)
                .onErrorResume(error -> error instanceof UniqueKeyViolationException unique
                        && (InboundEntities.EXTERNAL_KEY.equals(unique.constraintName())
                            || InboundEntities.BODY_KEY.equals(unique.constraintName())),
                    duplicate -> Mono.just(Outcome.DUPLICATE))
                .contextWrite(context -> RequestContexts.put(context, system));
        });
    }

    /** The request's Content-Type as kept: visible ASCII and spaces only, at most 100 characters; null if none. */
    static String contentType(String header) {
        if (header == null) {
            return null;
        }
        StringBuilder kept = new StringBuilder();
        for (int i = 0; i < header.length() && kept.length() < MAX_CONTENT_TYPE; i++) {
            char c = header.charAt(i);
            if (c >= 0x20 && c <= 0x7E) {
                kept.append(c);
            }
        }
        String text = kept.toString().trim();
        return text.isEmpty() ? null : text;
    }
}
