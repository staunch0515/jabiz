package com.jabiz.runtime.test.scenario;

import com.jabiz.runtime.test.PostgresTestDatabase;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Starts the application for one replay in a schema of its own (docs/design/07-quality.md section 3): Flyway
 * migrates the same scripts as for {@code PostgresIntegrationTest} (platform, application, test fixtures), so every
 * replay starts from the same database and gets the same operation numbers and keys. The context has no web server; the clock is the
 * test {@code MutableClock} and keys come from {@link DeterministicIdGenerator}. Closing it drops the schema.
 */
public final class ScenarioContexts {

    /** A running replay context; close it to stop the application and drop its schema. */
    public record Started(ConfigurableApplicationContext context, String schema) implements AutoCloseable {
        @Override
        public void close() {
            try {
                context.close();
            } finally {
                PostgresTestDatabase.get().dropSchema(schema);
            }
        }
    }

    private ScenarioContexts() {}

    public static Started start(Class<?> application) {
        PostgresTestDatabase db = PostgresTestDatabase.get();
        String schema = "sc_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("spring.r2dbc.url", db.r2dbcUrl() + "?schema=" + schema);
        properties.put("spring.r2dbc.username", db.username());
        properties.put("spring.r2dbc.password", db.password());
        properties.put("spring.flyway.url", db.jdbcUrl());
        properties.put("spring.flyway.user", db.username());
        properties.put("spring.flyway.password", db.password());
        properties.put("spring.flyway.schemas", schema);
        properties.put("spring.flyway.default-schema", schema);
        properties.put("spring.flyway.create-schemas", "true");
        properties.put("spring.flyway.locations", "classpath:db/migration,classpath:db/testmigration");
        properties.put("spring.main.banner-mode", "off");
        try {
            ConfigurableApplicationContext context = new SpringApplicationBuilder(application)
                .web(WebApplicationType.NONE)
                .initializers(ctx -> ((GenericApplicationContext) ctx).registerBean(DeterministicIdGenerator.class))
                // As arguments: default properties would lose to the application's own configuration.
                .run(properties.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue())
                    .toArray(String[]::new));
            return new Started(context, schema);
        } catch (RuntimeException e) {
            db.dropSchema(schema);
            throw e;
        }
    }
}
