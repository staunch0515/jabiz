package com.jabiz.runtime.test;

import com.jabiz.query.template.PublicQueryCatalog;
import com.jabiz.runtime.publicread.PublicProperties;
import com.jabiz.runtime.query.SqlTemplateRegistry;
import org.springframework.context.ApplicationContext;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * The catalog of an application's public templates, compared with the snapshot in the repository that public
 * frontends generate their types from (docs/design/15-public-access.md section 7), as the OpenAPI document is. The
 * snapshot path comes from {@code public-queries.snapshot} ({@code jabizApp.publicQueriesSnapshot});
 * {@code -Dpublic-queries.update-snapshot=true} rewrites it, and a missing one is written, except on CI.
 */
public final class PublicQueriesSnapshot {

    private static final ObjectMapper JSON = JsonMapper.builder()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .build();

    private PublicQueriesSnapshot() {}

    /** The catalog of the application's public templates, as the snapshot holds it. */
    public static String render(ApplicationContext context) {
        SqlTemplateRegistry templates = context.getBean(SqlTemplateRegistry.class);
        PublicProperties properties = context.getBean(PublicProperties.class);
        return JSON.writeValueAsString(PublicQueryCatalog.export(templates.all(), properties.defaultCacheSeconds()))
            + "\n";
    }

    /** Fails when the committed snapshot differs from the application's public templates. */
    public static void verify(ApplicationContext context) {
        String actual = render(context);
        Path snapshot = Path.of(System.getProperty("public-queries.snapshot",
            "../../frontend/openapi/public-queries.json"));
        boolean update = Boolean.getBoolean("public-queries.update-snapshot");
        try {
            if (!Files.exists(snapshot) && System.getenv("CI") != null && !update) {
                fail(snapshot + " is missing; generate it with ./gradlew :<app>:test --tests '*PublicQueriesSnapshotIT'"
                    + " -Dpublic-queries.update-snapshot=true and commit it");
            }
            if (update || !Files.exists(snapshot)) {
                Files.createDirectories(snapshot.toAbsolutePath().getParent());
                Files.writeString(snapshot, actual, StandardCharsets.UTF_8);
            }
            assertThat(Files.readString(snapshot, StandardCharsets.UTF_8))
                .as("The public templates changed: run the test with -Dpublic-queries.update-snapshot=true, "
                    + "regenerate the public frontend's types and commit both")
                .isEqualTo(actual);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
