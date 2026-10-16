package com.jabiz.runtime.web;

import com.jabiz.context.RequestContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.RateLimitedException;
import com.jabiz.runtime.observability.PlatformObservations;
import io.micrometer.common.KeyValues;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Rate limits of the process API, {@code POST /api/processes/{name}/...} (decision D45 item 5; docs/design/10-security.md
 * section 15): per actor, per client address and per instance, as {@code jabiz.process.rate-limits.<process>} declares,
 * each optional. Beyond a limit, 429 {@code RATE_LIMITED} with {@code Retry-After}. Checked after the permissions
 * (a request without them is a 403 and takes nothing of anyone's bucket) and before anything reads the database, so a
 * caller flooding a process holds neither connections nor its lock. Sub-processes, jobs, event consumers and the
 * platform's own entry points are not limited here.
 *
 * <p>Kept in memory, per instance, as the other limits (docs/design/15-public-access.md section 6); the buckets are the
 * same {@link TokenBucketLimiter} as those of the account endpoints ({@code SignUpRateLimits}). Not admission: a limit
 * stops one caller asking too much, the admission queue stops everyone crowding one lock.
 */
@Component
public class ProcessRateLimits {

    /** Keys kept per bucket; the least recently used are forgotten first. */
    static final int MAX_KEYS = 100_000;

    /** What a bucket counts by, the tag {@code scope} of {@value PlatformObservations#PROCESS_RATE_LIMITED}. */
    public enum Scope { USER, ADDRESS, INSTANCE }

    private record Buckets(TokenBucketLimiter perUser, TokenBucketLimiter perAddress, TokenBucketLimiter perInstance) {}

    private final Map<String, Buckets> buckets = new HashMap<>();
    private final ClientAddresses addresses;
    private final PlatformObservations observations;

    @Autowired
    public ProcessRateLimits(ProcessRateLimitProperties properties, ClientAddresses addresses,
        PlatformObservations observations) {
        this(properties, addresses, observations, System::nanoTime);
    }

    /** As above, timed by {@code nanoTime} (tests). */
    public ProcessRateLimits(ProcessRateLimitProperties properties, ClientAddresses addresses,
        PlatformObservations observations, LongSupplier nanoTime) {
        this.addresses = addresses;
        this.observations = Objects.requireNonNull(observations, "observations");
        properties.rateLimits().forEach((process, limit) -> {
            Duration period = limit.period();
            // A value out of range is the startup check's to report (ProcessAdmissionChecks); it limits nothing.
            if (period == null || period.isNegative() || period.isZero()) {
                return;
            }
            buckets.put(process, new Buckets(bucket(limit.perUser(), period, nanoTime),
                bucket(limit.perAddress(), period, nanoTime), bucket(limit.perInstance(), period, nanoTime)));
        });
    }

    private static TokenBucketLimiter bucket(Integer capacity, Duration period, LongSupplier nanoTime) {
        return capacity == null || capacity <= 0 ? null
            : new TokenBucketLimiter(capacity, period, MAX_KEYS, nanoTime);
    }

    /** Whether any limit applies to {@code process}. */
    public boolean limits(String process) {
        return buckets.containsKey(process);
    }

    /**
     * Counts a request of {@code request}'s actor to run {@code definition}; {@link RateLimitedException} (429) beyond
     * one of its limits. A process without limits is not counted.
     *
     * @param http the request, for the client address; null for none (counted as {@code unknown})
     */
    public void check(ProcessDefinition<?, ?, ?> definition, RequestContext request, ServerHttpRequest http) {
        Buckets limits = buckets.get(definition.name());
        if (limits == null) {
            return;
        }
        String process = definition.name();
        if (limits.perUser() != null) {
            take(limits.perUser(), process, Scope.USER,
                "user:" + Objects.requireNonNullElse(request.actorId(), "anonymous"));
        }
        if (limits.perAddress() != null) {
            String client = http == null || addresses == null ? null : addresses.clientOf(http);
            take(limits.perAddress(), process, Scope.ADDRESS, "address:" + Objects.requireNonNullElse(client,
                "unknown"));
        }
        if (limits.perInstance() != null) {
            take(limits.perInstance(), process, Scope.INSTANCE, "instance");
        }
    }

    private void take(TokenBucketLimiter limiter, String process, Scope scope, String key) {
        TokenBucketLimiter.Decision decision = limiter.tryAcquire(key);
        if (!decision.allowed()) {
            observations.event(PlatformObservations.PROCESS_RATE_LIMITED,
                KeyValues.of("process", process, "scope", scope.name().toLowerCase(Locale.ROOT)));
            throw new RateLimitedException("Too many requests to run " + process + "; try again in "
                + decision.retryAfterSeconds() + " seconds", decision.retryAfterSeconds());
        }
    }
}
