package com.jabiz.runtime.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

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
     */
    public record Limit(Integer perUser, Integer perAddress, Integer perInstance, Duration period) {
        public Limit {
            period = period == null ? DEFAULT_PERIOD : period;
        }
    }
}
