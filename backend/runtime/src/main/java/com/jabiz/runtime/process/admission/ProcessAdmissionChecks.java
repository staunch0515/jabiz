package com.jabiz.runtime.process.admission;

import com.jabiz.process.Admission;
import com.jabiz.process.ProcessAccess;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import com.jabiz.runtime.process.ProcessChecks;
import com.jabiz.runtime.process.ProcessRegistry;
import com.jabiz.runtime.web.ProcessRateLimitProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Startup checks of the admission queues and the process API's rate limits (decision D45), category {@code PROCESS}:
 * <ul>
 *   <li>a declared family is a valid name ({@link Admission#FAMILY});</li>
 *   <li>a {@code subProcessOnly()} process declares no admission: sub-processes never queue;</li>
 *   <li>a configured family is declared by some process (a warning: a typo, or a declaration since removed);</li>
 *   <li>the settings are in range: {@code concurrency} 1 to 16, {@code max-queue} 0 to 10 000, {@code max-wait}
 *       positive and at most {@code jabiz.process.lock-timeout}, {@code retry-after} 0 to 30 seconds,
 *       {@code max-active} 0 (half the pool) or 1 to the pool's size less one, {@code max-keys} and
 *       {@code max-waiting} positive;</li>
 *   <li>a rate limit names a registered process of the process catalog (not {@code internal()},
 *       {@code entryOnly()} or {@code subProcessOnly()}: those never come through the process API), with positive
 *       values and at least one bucket.</li>
 * </ul>
 */
@Component
public class ProcessAdmissionChecks implements PlatformCheck {

    static final int MAX_CONCURRENCY = 16;
    static final int MAX_QUEUE = 10_000;
    static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(AdmissionGate.MAX_RETRY_AFTER_SECONDS);

    private final ProcessRegistry registry;
    private final AdmissionSettings settings;
    private final ProcessRateLimitProperties rateLimits;
    private final int poolSize;
    private final Duration lockTimeout;

    public ProcessAdmissionChecks(ProcessRegistry registry, AdmissionSettings settings,
        ProcessRateLimitProperties rateLimits, @Value("${spring.r2dbc.pool.max-size:10}") int poolSize,
        @Value("${jabiz.process.lock-timeout:30s}") Duration lockTimeout) {
        this.registry = registry;
        this.settings = settings;
        this.rateLimits = rateLimits;
        this.poolSize = poolSize;
        this.lockTimeout = lockTimeout;
    }

    @Override
    public List<CheckProblem> check() {
        List<CheckProblem> problems = new ArrayList<>();
        Set<String> declared = new TreeSet<>();
        for (ProcessDefinition<?, ?, ?> definition : registry.all()) {
            Admission<?> admission = definition.admission();
            if (admission == null) {
                continue;
            }
            String location = definition.name() + " v" + definition.version();
            declared.add(admission.family());
            if (!admission.validFamily()) {
                problems.add(error(location, "admission family '" + admission.family() + "' is not a valid name ("
                    + Admission.FAMILY.pattern() + ")"));
            }
            if (definition.access() == ProcessAccess.SUB_PROCESS_ONLY) {
                problems.add(error(location, "is subProcessOnly() and declares an admission: sub-processes run in "
                    + "their root's transaction and never queue (decision D45); declare it on the root process"));
            }
        }
        checkSettings(declared, problems);
        checkRateLimits(problems);
        return problems;
    }

    private void checkSettings(Set<String> declared, List<CheckProblem> problems) {
        String where = "jabiz.process.admission";
        checkFamily(where + ".defaults", settings.defaults(), problems);
        new TreeMap<>(settings.families()).forEach((family, limits) -> {
            String location = where + ".families[" + family + "]";
            if (!declared.contains(family)) {
                problems.add(CheckProblem.warning(ProcessChecks.CATEGORY, location,
                    "no process declares admission family " + family));
            }
            checkFamily(location, limits.over(settings.defaults()), problems);
        });
        int maxActive = settings.maxActive();
        if (maxActive < 0 || maxActive > 0 && maxActive > poolSize - 1) {
            problems.add(error(where + ".max-active", "must be 0 (half of spring.r2dbc.pool.max-size) or 1 to "
                + Math.max(1, poolSize - 1) + " (the pool's size less one, so reads always have a connection), not "
                + maxActive));
        }
        if (settings.maxKeys() < 1) {
            problems.add(error(where + ".max-keys", "must be positive, not " + settings.maxKeys()));
        }
        if (settings.maxWaiting() < 1) {
            problems.add(error(where + ".max-waiting", "must be positive, not " + settings.maxWaiting()));
        }
    }

    private void checkFamily(String location, AdmissionSettings.Family limits, List<CheckProblem> problems) {
        if (limits.concurrency() < 1 || limits.concurrency() > MAX_CONCURRENCY) {
            problems.add(error(location + ".concurrency", "must be 1 to " + MAX_CONCURRENCY + ", not "
                + limits.concurrency()));
        }
        if (limits.maxQueue() < 0 || limits.maxQueue() > MAX_QUEUE) {
            problems.add(error(location + ".max-queue", "must be 0 to " + MAX_QUEUE + ", not " + limits.maxQueue()));
        }
        Duration wait = limits.maxWait();
        if (wait == null || wait.isNegative() || wait.isZero() || wait.compareTo(lockTimeout) > 0) {
            problems.add(error(location + ".max-wait", "must be positive and at most jabiz.process.lock-timeout ("
                + lockTimeout + "), not " + wait));
        }
        Duration retryAfter = limits.retryAfter();
        if (retryAfter == null || retryAfter.isNegative() || retryAfter.compareTo(MAX_RETRY_AFTER) > 0) {
            problems.add(error(location + ".retry-after", "must be 0 to " + MAX_RETRY_AFTER + ", not "
                + retryAfter));
        }
    }

    private void checkRateLimits(List<CheckProblem> problems) {
        for (Map.Entry<String, ProcessRateLimitProperties.Limit> entry
            : new TreeMap<>(rateLimits.rateLimits()).entrySet()) {
            String location = "jabiz.process.rate-limits." + entry.getKey();
            ProcessRateLimitProperties.Limit limit = entry.getValue();
            var found = registry.findLatest(entry.getKey());
            if (found.isEmpty()) {
                problems.add(error(location, "names no registered process"));
            } else if (!found.get().listed() && found.get().access() != ProcessAccess.APP_PROXY) {
                problems.add(error(location, entry.getKey() + " is " + found.get().access() + " (decision D41): "
                    + "it never comes through the process API or an app proxy, which is all a rate limit counts"));
            } else if (found.get().access() != ProcessAccess.APP_PROXY
                && (limit.perTenant() != null || limit.perVisitor() != null)) {
                problems.add(error(location, "per-tenant and per-visitor count the callers of an app proxy only"
                    + " (decision D60)"));
            } else if (found.get().access() == ProcessAccess.APP_PROXY && limit.perUser() != null) {
                // Every anonymous visitor of every tenant is one actor: count per tenant and visitor instead.
                problems.add(error(location, "per-user does not tell an app proxy's callers apart: use per-tenant"
                    + " and per-visitor (decision D60)"));
            }
            if (limit.perUser() == null && limit.perAddress() == null && limit.perInstance() == null
                && limit.perTenant() == null && limit.perVisitor() == null) {
                problems.add(error(location, "declares none of per-user, per-address, per-instance, per-tenant and"
                    + " per-visitor"));
            }
            positive(location + ".per-user", limit.perUser(), problems);
            positive(location + ".per-address", limit.perAddress(), problems);
            positive(location + ".per-instance", limit.perInstance(), problems);
            positive(location + ".per-tenant", limit.perTenant(), problems);
            positive(location + ".per-visitor", limit.perVisitor(), problems);
            if (limit.period().isNegative() || limit.period().isZero()) {
                problems.add(error(location + ".period", "must be positive, not " + limit.period()));
            }
        }
    }

    private static void positive(String location, Integer value, List<CheckProblem> problems) {
        if (value != null && value <= 0) {
            problems.add(error(location, "must be positive, not " + value));
        }
    }

    private static CheckProblem error(String location, String message) {
        return CheckProblem.error(ProcessChecks.CATEGORY, location, message);
    }
}
