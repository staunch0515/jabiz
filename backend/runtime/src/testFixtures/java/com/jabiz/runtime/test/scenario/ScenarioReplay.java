package com.jabiz.runtime.test.scenario;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Entry point of scenario replay tests (docs/design/07-quality.md section 3):
 * <pre>
 * &#64;TestFactory
 * Stream&lt;DynamicTest&gt; scenarios() {
 *     return ScenarioReplay.resources("scenarios").stream()
 *         .map(resource -&gt; DynamicTest.dynamicTest(resource, () -&gt; ScenarioReplay.verify(App.class, resource)));
 * }
 * </pre>
 * Every replay runs in a fresh application context on a schema of its own ({@link ScenarioContexts}).
 */
public final class ScenarioReplay {

    private ScenarioReplay() {}

    /** Classpath resources {@code <root>/**}{@code /*.yml}, as paths relative to the classpath root, sorted. */
    public static List<String> resources(String root) {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            // Paths are taken relative to each classpath location of the root, so that nested directories that
            // happen to have the root's name do not confuse them.
            List<String> paths = new ArrayList<>();
            for (Resource base : resolver.getResources("classpath*:" + root + "/")) {
                String baseUrl = base.getURL().toString();
                for (Resource resource : resolver.getResources("classpath*:" + root + "/**/*.yml")) {
                    String url = resource.getURL().toString();
                    if (url.startsWith(baseUrl)) {
                        paths.add(root + "/" + url.substring(baseUrl.length()).replaceFirst("^/", ""));
                    }
                }
            }
            return paths.stream().sorted().distinct().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The scenario in a classpath resource. */
    public static Scenario load(String resource) {
        return Scenario.parse(text(resource), resource);
    }

    /** The text of a classpath resource, for tests that vary a scenario before parsing it. */
    public static String text(String resource) {
        try (InputStream in = ScenarioReplay.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("No scenario " + resource + " on the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Replays the scenario in a fresh context and returns what it produced. */
    public static ScenarioRunner.Result replay(Class<?> application, Scenario scenario) {
        try (ScenarioContexts.Started started = ScenarioContexts.start(application)) {
            return new ScenarioRunner(started.context()).run(scenario);
        }
    }

    /** Replays the scenario in the resource and compares the result with its snapshot ({@link SnapshotStore}). */
    public static void verify(Class<?> application, String resource) {
        Scenario scenario = load(resource);
        ScenarioRunner.Result result = replay(application, scenario);
        if (scenario.snapshot() != null) {
            SnapshotStore.verify(result);
        }
    }
}
