package com.jabiz.runtime.test.scenario;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Snapshot files next to their scenarios, {@code <name>.snapshot.json} for {@code <name>.yml}
 * (docs/design/07-quality.md section 3.3). Expected snapshots are read from the source tree when the build names it
 * (system property {@value #RESOURCES_DIR}), else from the classpath; only the source tree is ever written.
 * <ul>
 *   <li>no snapshot yet: it is written and the replay passes, except on CI (environment variable {@code CI}), where
 *       a missing snapshot fails: snapshots are reviewed with the change that creates them;</li>
 *   <li>a different snapshot fails with the lines of {@link SnapshotDiff}, unless
 *       {@code -D}{@value #UPDATE}{@code =true} asks to accept it, which rewrites the file.</li>
 * </ul>
 */
public final class SnapshotStore {

    /** System property: directory of the test resources in the source tree. */
    public static final String RESOURCES_DIR = "scenario.resources-dir";
    /** System property: {@code true} rewrites snapshots that differ. */
    public static final String UPDATE = "scenario.update-snapshots";

    private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    private SnapshotStore() {}

    /** The snapshot document of a replay: scenario name, time and rows by entity. */
    public static Map<String, Object> document(ScenarioRunner.Result result) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("scenario", result.scenario().name());
        document.put("asOf", String.valueOf(result.snapshotAsOf()));
        document.put("entities", result.snapshot());
        return document;
    }

    /** The document as it is stored: indented JSON with a final newline. */
    public static String render(Map<String, Object> document) {
        return JSON.writeValueAsString(document) + "\n";
    }

    /** Compares the replay with its stored snapshot, creating or updating the file as described above. */
    public static void verify(ScenarioRunner.Result result) {
        String resource = snapshotResource(result.scenario().source());
        Map<String, Object> actual = parse(render(document(result)));
        String stored = read(resource);
        if (stored == null) {
            if (System.getenv("CI") != null) {
                throw new AssertionError("No snapshot " + resource + " for " + result.scenario().source()
                    + ": run the scenario locally and commit the snapshot it writes");
            }
            write(resource, render(document(result)));
            return;
        }
        List<String> differences = differences(parse(stored), actual);
        if (differences.isEmpty()) {
            return;
        }
        if (Boolean.getBoolean(UPDATE)) {
            write(resource, render(document(result)));
            return;
        }
        throw new AssertionError("Snapshot " + resource + " differs (accept with -D" + UPDATE + "=true):\n  "
            + String.join("\n  ", differences));
    }

    /** Differences between two snapshot documents, the scenario time first. */
    @SuppressWarnings("unchecked")
    public static List<String> differences(Map<String, Object> expected, Map<String, Object> actual) {
        List<String> lines = new ArrayList<>();
        if (!String.valueOf(expected.get("asOf")).equals(String.valueOf(actual.get("asOf")))) {
            lines.add("~ asOf: " + expected.get("asOf") + " → " + actual.get("asOf"));
        }
        lines.addAll(SnapshotDiff.between(
            (Map<String, List<Map<String, Object>>>) expected.getOrDefault("entities", Map.of()),
            (Map<String, List<Map<String, Object>>>) actual.getOrDefault("entities", Map.of())));
        return lines;
    }

    public static Map<String, Object> parse(String json) {
        return JSON.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() { });
    }

    /** {@code scenarios/a/b.yml} → {@code scenarios/a/b.snapshot.json}. */
    static String snapshotResource(String scenarioResource) {
        int dot = scenarioResource.lastIndexOf('.');
        return (dot < 0 ? scenarioResource : scenarioResource.substring(0, dot)) + ".snapshot.json";
    }

    private static String read(String resource) {
        Path source = sourcePath(resource);
        try {
            if (source != null) {
                return Files.exists(source) ? Files.readString(source, StandardCharsets.UTF_8) : null;
            }
            try (InputStream in = SnapshotStore.class.getClassLoader().getResourceAsStream(resource)) {
                return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(String resource, String content) {
        Path source = sourcePath(resource);
        if (source == null) {
            throw new AssertionError("Cannot write snapshot " + resource + ": system property " + RESOURCES_DIR
                + " does not name the test resources directory (run through Gradle). Snapshot:\n" + content);
        }
        try {
            Files.createDirectories(source.getParent());
            Files.writeString(source, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path sourcePath(String resource) {
        String dir = System.getProperty(RESOURCES_DIR);
        return dir == null || dir.isBlank() ? null : Path.of(dir).resolve(resource);
    }
}
