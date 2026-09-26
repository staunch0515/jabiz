package com.jabiz.runtime.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * Defaults of third-party libraries that the platform relies on for safety, added with the lowest precedence so an
 * application can still override them deliberately.
 *
 * <p>The OpenAPI document is served under {@code /api}, where authentication is required (default deny,
 * docs/design/10-security.md section 1); springdoc's own default path lies outside {@code /api} and would be public.
 */
public class JabizDefaultProperties implements EnvironmentPostProcessor {

    static final String SOURCE = "jabizDefaults";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        environment.getPropertySources().addLast(new MapPropertySource(SOURCE, Map.of(
            "springdoc.api-docs.path", "/api/meta/openapi",
            "springdoc.swagger-ui.enabled", "false",
            "springdoc.writer-with-order-by-keys", "true",
            "springdoc.default-produces-media-type", "application/json",
            // Built once at startup: building it scans the classpath, which must not happen on the event loop.
            "springdoc.pre-loading-enabled", "true",
            "management.otlp.metrics.export.enabled", "false",
            // Latency percentiles of the platform's units of work and of requests, computed by the metrics backend.
            "management.metrics.distribution.percentiles-histogram.jabiz", "true",
            "management.metrics.distribution.percentiles-histogram.http.server.requests", "true",
            "management.observations.r2dbc.include-parameter-values", "false")));
    }
}
