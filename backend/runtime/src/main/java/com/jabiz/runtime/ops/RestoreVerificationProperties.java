package com.jabiz.runtime.ops;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * With {@code jabiz.ops.verify-restore.enabled=true} (a restored copy, docs/design/13-observability-ops.md section 8), turns
 * off everything that would act on the outside world - mail, push, scheduled jobs, event delivery (and with it
 * webhooks), external jobs - with the highest precedence, so that no setting copied from production can turn them
 * back on, and serves only on the loopback interface, on a free port.
 */
public class RestoreVerificationProperties implements EnvironmentPostProcessor {

    static final String SOURCE = "jabizRestoreVerification";

    /** What a verification run forces, whatever else is configured. */
    static final Map<String, Object> FORCED = Map.of(
        "jabiz.mail.enabled", "false",
        "jabiz.push.enabled", "false",
        "jabiz.jobs.scheduler.enabled", "false",
        "jabiz.events.delivery.enabled", "false",
        "jabiz.external-jobs.executor.enabled", "false",
        "server.address", "127.0.0.1",
        "server.port", "0");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.getProperty(RestoreVerifier.ENABLED, Boolean.class, false)) {
            environment.getPropertySources().addFirst(new MapPropertySource(SOURCE, FORCED));
        }
    }
}
