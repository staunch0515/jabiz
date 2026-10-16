package com.jabiz.runtime.process.admission;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;

/**
 * The queues before hot named locks ({@code jabiz.process.admission.*}; docs/design/06-process.md section 4.1, decision
 * D45). Values out of range are reported by the startup check ({@link ProcessAdmissionChecks}), not refused here.
 *
 * @param enabled    the emergency switch: false admits every process at once, as without declarations
 * @param maxActive  permits held at once over all keys of this instance; 0 (the default) for half of
 *                   {@code spring.r2dbc.pool.max-size}, at least 1, so reads always have the other half of the pool
 * @param maxKeys    keys with holders or waiters at once; a new key beyond it is refused ({@code QUEUE_FULL})
 * @param maxWaiting waiters at once over all keys; one more is refused ({@code QUEUE_FULL})
 * @param defaults   the limits of a family not configured on its own
 * @param families   limits by family; a family with dots is written in brackets ({@code "[qb.pub]"}); what a family
 *                   leaves out comes from {@code defaults}
 */
@ConfigurationProperties("jabiz.process.admission")
public record AdmissionSettings(Boolean enabled, Integer maxActive, Integer maxKeys, Integer maxWaiting,
    Family defaults, Map<String, Family> families) {

    public static final int DEFAULT_CONCURRENCY = 2;
    public static final int DEFAULT_MAX_QUEUE = 50;
    public static final Duration DEFAULT_MAX_WAIT = Duration.ofSeconds(2);
    public static final Duration DEFAULT_RETRY_AFTER = Duration.ofSeconds(1);
    public static final int DEFAULT_MAX_KEYS = 10_000;
    public static final int DEFAULT_MAX_WAITING = 5_000;

    /** As if nothing were configured. */
    public static final AdmissionSettings DEFAULT = new AdmissionSettings(null, null, null, null, null, null);

    public AdmissionSettings {
        enabled = enabled == null || enabled;
        maxActive = maxActive == null ? 0 : maxActive;
        maxKeys = maxKeys == null ? DEFAULT_MAX_KEYS : maxKeys;
        maxWaiting = maxWaiting == null ? DEFAULT_MAX_WAITING : maxWaiting;
        Family fallback = new Family(DEFAULT_CONCURRENCY, DEFAULT_MAX_QUEUE, DEFAULT_MAX_WAIT, DEFAULT_RETRY_AFTER);
        defaults = defaults == null ? fallback : defaults.over(fallback);
        families = families == null ? Map.of() : Map.copyOf(families);
    }

    /** The limits of {@code family}: its own where it sets them, the defaults elsewhere. */
    public Family limitsOf(String family) {
        Family own = families.get(family);
        return own == null ? defaults : own.over(defaults);
    }

    /** The permits all keys may hold at once, given the size of the connection pool. */
    public int maxActiveFor(int poolSize) {
        return maxActive > 0 ? maxActive : Math.max(1, poolSize / 2);
    }

    @Override
    public String toString() {
        return "AdmissionSettings[enabled=" + enabled + ", maxActive=" + maxActive + ", maxKeys=" + maxKeys
            + ", maxWaiting=" + maxWaiting + ", defaults=" + defaults + ", families=" + new TreeMap<>(families) + "]";
    }

    /**
     * The limits of one family, per key and instance.
     *
     * @param concurrency runs of one key in their transaction at once (default 2: the second does its work before the
     *                    lock while the first holds it, and takes over as soon as it commits)
     * @param maxQueue    waiters of one key; one more is refused at once ({@code QUEUE_FULL}); 0 queues none
     * @param maxWait     the longest a run waits in the queue ({@code WAIT_TIMEOUT}), at most
     *                    {@code jabiz.process.lock-timeout}; a job item's shorter lock wait shortens it
     * @param retryAfter  the least {@code Retry-After} of a refusal; the estimate from the queue may be longer, up to
     *                    30 seconds
     */
    public record Family(Integer concurrency, Integer maxQueue, Duration maxWait, Duration retryAfter) {

        /** These limits, with what they leave out taken from {@code base}. */
        Family over(Family base) {
            return new Family(concurrency == null ? base.concurrency : concurrency,
                maxQueue == null ? base.maxQueue : maxQueue,
                maxWait == null ? base.maxWait : maxWait,
                retryAfter == null ? base.retryAfter : retryAfter);
        }
    }
}
