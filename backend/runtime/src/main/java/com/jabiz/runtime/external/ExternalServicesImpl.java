package com.jabiz.runtime.external;

import com.jabiz.external.ExternalHttp;
import com.jabiz.external.ExternalService;
import com.jabiz.external.ExternalServices;
import com.jabiz.runtime.observability.PlatformObservations;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The declared external services for blocking steps (decision D38): one {@link JdkExternalHttp}, and so one JDK
 * client, per service, created on first use (a declaration the startup check refuses never gets one).
 */
@Component
public class ExternalServicesImpl implements ExternalServices, AutoCloseable {

    /** Suffixes of {@link #idempotencyKey}: the key stays within what providers accept. */
    static final Pattern SUFFIX = Pattern.compile("[A-Za-z0-9._:-]{1,100}");

    private final ExternalServiceRegistry registry;
    private final PlatformObservations observations;
    private final Map<String, JdkExternalHttp> clients = new ConcurrentHashMap<>();

    public ExternalServicesImpl(ExternalServiceRegistry registry, PlatformObservations observations) {
        this.registry = registry;
        this.observations = observations;
    }

    @Override
    public ExternalHttp http(String service) {
        ExternalService declared = declared(service);
        return clients.computeIfAbsent(declared.name(), name -> new JdkExternalHttp(declared, observations));
    }

    @Override
    public String secret(String service, String name) {
        ExternalCallScope scope = scope("secret");
        ExternalService declared = declared(service);
        if (!scope.allows(service)) {
            throw new IllegalArgumentException("External service " + service + " is not among the services of "
                + scope.stepName());
        }
        String variable = declared.secrets().get(name);
        if (variable == null) {
            throw new IllegalArgumentException("External service " + service + " declares no secret " + name);
        }
        return registry.secret(service, name).orElseThrow(() -> new IllegalStateException("Secret " + name
            + " of external service " + service + " is not set: environment variable " + variable
            + " (step '" + scope.stepName() + "')"));
    }

    @Override
    public String idempotencyKey(String suffix) {
        ExternalCallScope scope = scope("idempotency key");
        if (suffix == null || !SUFFIX.matcher(suffix).matches()) {
            throw new IllegalArgumentException("Idempotency key suffixes are 1 to 100 characters of letters, digits, "
                + "'.', '_', ':' and '-'");
        }
        // Within a job's handler the key is the job's own: the same for every attempt.
        return scope.jobId().map(job -> "jabiz-job-" + job + "-" + suffix)
            .orElseGet(() -> "jabiz-" + scope.processSeqId() + "-" + suffix);
    }

    private static ExternalCallScope scope(String what) {
        return ExternalCallScope.current().orElseThrow(() -> new IllegalStateException("An external service's "
            + what + " is only available within a blocking step"));
    }

    private ExternalService declared(String service) {
        return registry.find(service).orElseThrow(() -> new IllegalArgumentException(
            "No external service " + service + " is declared"));
    }

    @PreDestroy
    @Override
    public void close() {
        clients.values().forEach(JdkExternalHttp::close);
        clients.clear();
    }

    @Override
    public String toString() {
        return "ExternalServices" + clients.keySet();
    }
}
