package com.jabiz.quizbuks.it;

import com.jabiz.context.RequestContext;
import com.jabiz.quizbuks.QuizbuksApp;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.firstlaunch.FirstLaunchExit;
import com.jabiz.runtime.firstlaunch.FirstLaunchRun;
import com.jabiz.runtime.firstlaunch.FirstLaunchRuns;
import com.jabiz.runtime.test.scenario.Scenario;
import com.jabiz.runtime.test.scenario.ScenarioContexts;
import com.jabiz.runtime.test.scenario.ScenarioReplay;
import com.jabiz.runtime.test.scenario.ScenarioRunner;
import com.jabiz.runtime.test.scenario.SnapshotStore;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-LAUNCH Q4 (docs/quizbuks/plans/impl/F-LAUNCH.md section 9, item 22): the replay of a first launch. The configuration
 * is the sample handed to the requester ({@code docs/quizbuks/first-launch-sample/}), used as it is, so the sample is
 * known to run. The content phase runs when the application starts (in the background, on the test clock's start), so
 * the test waits for it before replaying {@code first-launch/scenario.yml} (the waiting phase); the generic
 * {@code ScenarioTest} has no such wait, which is why this scenario is not under {@code scenarios/}.
 */
class FirstLaunchScenarioTest {

    static final String SCENARIO = "first-launch/scenario.yml";
    /** The sample, from the module's directory (where Gradle runs the tests). */
    static final Path SAMPLE = Path.of("../../docs/quizbuks/first-launch-sample/first-launch.yml");
    static final Duration CONTENT_PHASE = Duration.ofMinutes(3);
    static final List<Integer> EXITS = new CopyOnWriteArrayList<>();

    /** A failed run ends the application ({@code System.exit} without this): the test records the code instead. */
    @Configuration(proxyBeanMethods = false)
    static class Exits {
        @Bean
        FirstLaunchExit firstLaunchScenarioExit() {
            return EXITS::add;
        }
    }

    private static final RequestContext SYSTEM = RequestContext.system(Locale.ENGLISH, "first-launch-scenario");

    @Test
    void theSampleLaunchesAndExpiresWithItsReport() throws Exception {
        assertThat(Files.isRegularFile(SAMPLE)).as("the sample " + SAMPLE.toAbsolutePath()).isTrue();
        Scenario scenario = ScenarioReplay.load(SCENARIO);
        Map<String, String> properties = new LinkedHashMap<>(scenario.properties());
        properties.put("jabiz.first-launch.config", SAMPLE.toAbsolutePath().normalize().toString());
        properties.put("spring.main.sources", Exits.class.getName());
        try (ScenarioContexts.Started started = ScenarioContexts.start(QuizbuksApp.class, properties)) {
            FirstLaunchRuns runs = started.context().getBean(FirstLaunchRuns.class);
            FirstLaunchRun run = awaitContent(runs);
            assertThat(run.state()).as("the content phase").isEqualTo(FirstLaunchRun.State.CONTENT_DONE);
            ScenarioRunner.Result result = new ScenarioRunner(started.context()).run(scenario);
            assertThat(current(runs).state()).as("after the deadline").isEqualTo(FirstLaunchRun.State.EXPIRED);
            assertThat(EXITS).isEmpty();
            SnapshotStore.verify(result);
        }
    }

    private static FirstLaunchRun awaitContent(FirstLaunchRuns runs) throws InterruptedException {
        long end = System.nanoTime() + CONTENT_PHASE.toNanos();
        while (true) {
            FirstLaunchRun run = runs.current().contextWrite(view -> RequestContexts.put(view, SYSTEM)).block()
                .orElse(null);
            if (run != null && run.state() != FirstLaunchRun.State.RUNNING) {
                return run;
            }
            if (!EXITS.isEmpty() || System.nanoTime() > end) {
                throw new AssertionError("The content phase did not finish (exits " + EXITS + "): " + run);
            }
            Thread.sleep(100);
        }
    }

    private static FirstLaunchRun current(FirstLaunchRuns runs) {
        return runs.current().contextWrite(view -> RequestContexts.put(view, SYSTEM)).block().orElseThrow();
    }
}
