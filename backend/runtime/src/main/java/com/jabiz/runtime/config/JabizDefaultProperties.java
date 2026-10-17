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
        environment.getPropertySources().addLast(new MapPropertySource(SOURCE, Map.ofEntries(
            Map.entry("springdoc.api-docs.path", "/api/meta/openapi"),
            Map.entry("springdoc.swagger-ui.enabled", "false"),
            Map.entry("springdoc.writer-with-order-by-keys", "true"),
            Map.entry("springdoc.default-produces-media-type", "application/json"),
            // The public API's contract is the public templates' catalog, not the admin frontend's document
            // (docs/design/15-public-access.md section 7).
            Map.entry("springdoc.paths-to-exclude", "/api/public/**"),
            // Built once at startup: building it scans the classpath, which must not happen on the event loop.
            Map.entry("springdoc.pre-loading-enabled", "true"),
            Map.entry("management.otlp.metrics.export.enabled", "false"),
            // Latency percentiles of the platform's units of work and of requests, computed by the metrics backend.
            Map.entry("management.metrics.distribution.percentiles-histogram.jabiz", "true"),
            Map.entry("management.metrics.distribution.percentiles-histogram.http.server.requests", "true"),
            Map.entry("management.observations.r2dbc.include-parameter-values", "false"),
            // The driver caches every distinct statement text it prepared, without bound by default (-1); the held
            // writes of a transaction (16p-2) make texts vary with the number of rows and parts, so the cache of each
            // connection is bounded (jabiz.storage.prepared-statement-cache-queries; 0 turns it off).
            Map.entry("spring.r2dbc.properties.preparedStatementCacheQueries",
                "${jabiz.storage.prepared-statement-cache-queries:256}"))));
    }
}
