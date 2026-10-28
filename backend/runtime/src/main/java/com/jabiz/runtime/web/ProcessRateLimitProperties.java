package com.jabiz.runtime.web;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;

/**
 * Rate limits of the process API ({@code jabiz.process.rate-limits.<process>}; docs/design/10-security.md section 15,
 * decision D45 item 5), declared in configuration only, so operations may change them without a build. Values that are
 * not positive, and processes not run through the process API, are reported by the startup check.
 *
 * @param rateLimits by process name
 */
@ConfigurationProperties("jabiz.process")
public record ProcessRateLimitProperties(Map<String, Limit> rateLimits) {

    /** The period of a limit that names none. */
    public static final Duration DEFAULT_PERIOD = Duration.ofMinutes(1);

    public ProcessRateLimitProperties {
        rateLimits = rateLimits == null ? Map.of() : Map.copyOf(rateLimits);
    }

    @Override
    public String toString() {
        return "ProcessRateLimitProperties[rateLimits=" + new TreeMap<>(rateLimits) + "]";
    }

    /**
     * Requests of one process per {@code period}, each kind of bucket optional.
     *
     * @param perUser     per actor
     * @param perAddress  per client address ({@code ClientAddresses}: forwarding headers count through trusted proxies
     *                    only)
     * @param perInstance of everyone together, on this instance
     * @param period      default one minute
     * @param perTenant   per tenant of an app proxy's caller (decision D60): app proxy processes only
     * @param perVisitor  per visitor key within the tenant (the query parameter the proxy names, decision D60): app
     *                    proxy processes only; the key is the visitor's own and proves nothing, so it only spreads the
     *                    tenant's allowance
     */
    public record Limit(Integer perUser, Integer perAddress, Integer perInstance, Duration period, Integer perTenant,
        Integer perVisitor) {

        // Bound through this constructor: the record has another one, so the binder must be told.
        @ConstructorBinding
        public Limit {
            period = period == null ? DEFAULT_PERIOD : period;
        }

        /** A limit of the process API: per actor, address and instance. */
        public Limit(Integer perUser, Integer perAddress, Integer perInstance, Duration period) {
            this(perUser, perAddress, perInstance, period, null, null);
        }
    }
}
