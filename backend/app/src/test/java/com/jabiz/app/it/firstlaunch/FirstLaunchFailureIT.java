package com.jabiz.app.it.firstlaunch;

import com.jabiz.context.RequestContext;
import com.jabiz.firstlaunch.FirstLaunchConfig;
import com.jabiz.firstlaunch.FirstLaunchHandler;
import com.jabiz.firstlaunch.FirstLaunchProblem;
import com.jabiz.firstlaunch.FirstLaunchStep;
import com.jabiz.i18n.MessageCatalog;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.firstlaunch.FirstLaunchExit;
import com.jabiz.runtime.firstlaunch.FirstLaunchGate;
import com.jabiz.runtime.firstlaunch.FirstLaunchPlatformSteps;
import com.jabiz.runtime.firstlaunch.FirstLaunchRun;
import com.jabiz.runtime.firstlaunch.FirstLaunchRunner;
import com.jabiz.runtime.firstlaunch.FirstLaunchRuns;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A content phase that fails (docs/design/23-first-launch.md section 4): the run is recorded as failed with the
 * step and the reason, the application ends with a non-zero code and never opens, and every later start fails the
 * same way, without resuming, until the database is emptied.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Import(FirstLaunchFailureIT.Handler.class)
class FirstLaunchFailureIT extends PostgresIntegrationTest {

    static final List<Integer> EXITS = new CopyOnWriteArrayList<>();

    @DynamicPropertySource
    static void firstLaunch(DynamicPropertyRegistry registry) {
        registry.add("jabiz.first-launch.config", () -> FirstLaunchIT.configFile("version: 1\nsite:\n  language: zh\n"));
    }

    public record Fail(String why) {}

    @TestConfiguration
    static class Handler {

        @Bean
        ProcessDefinition<Fail, Fail, ProcessContext> firstLaunchItFail() {
            return ProcessDefinition.define("FL_IT_FAIL", 1, Fail.class, Fail.class, ProcessContext.class, pb -> pb
                .permissions("test.first-launch")
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put("in", input);
                    return ctx;
                })
                .compute("Fail", (metadata, ctx) -> {
                    throw new IllegalStateException(ctx.get("in", Fail.class).why());
                })
                .outputMapper(ctx -> ctx.get("in", Fail.class)));
        }

        @Bean
        FirstLaunchHandler firstLaunchItHandler() {
            return new FirstLaunchHandler() {
                @Override
                public String name() {
                    return "it";
                }

                @Override
                public List<FirstLaunchProblem> validate(FirstLaunchConfig config) {
                    return List.of();
                }

                @Override
                public List<FirstLaunchStep> plan(FirstLaunchConfig config) {
                    return List.of(FirstLaunchStep.run("broken", "FL_IT_FAIL",
                        results -> new Fail("the quiz file of sponsor hq has no questions")));
                }
            };
        }

        @Bean
        FirstLaunchExit firstLaunchItExit() {
            return EXITS::add;
        }
    }

    @Autowired
    FirstLaunchGate gate;

    @Autowired
    FirstLaunchRuns runs;

    @Autowired
    Environment environment;

    @Autowired
    ProcessExecutor processes;

    @Autowired
    ObjectProvider<FirstLaunchHandler> handlers;

    @Autowired
    ObjectProvider<FirstLaunchPlatformSteps> platformSteps;

    @Autowired
    ObjectProvider<FirstLaunchExit> exits;

    @Autowired
    MessageCatalog messages;

    @Autowired
    Clock clockBean;

    @Autowired
    JsonMapper json;

    @Test
    void aFailedStepEndsTheRunAndEveryLaterStartFails() {
        long end = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (EXITS.isEmpty() && System.nanoTime() < end) {
            Thread.onSpinWait();
        }

        assertThat(EXITS).containsExactly(1);
        assertThat(gate.isOpen()).isFalse();
        FirstLaunchRun run = runs.current()
            .contextWrite(view -> RequestContexts.put(view, RequestContext.system(Locale.ENGLISH, "it")))
            .block().orElseThrow();
        assertThat(run.state()).isEqualTo(FirstLaunchRun.State.FAILED);
        assertThat(run.failedStep()).isEqualTo("broken");
        assertThat(run.failedReason()).contains("the quiz file of sponsor hq has no questions");
        // The failed step's writes were rolled back with it; only the run's own record is left.
        assertThat(query("SELECT count(*) AS steps FROM op_process WHERE process_name = 'FL_IT_FAIL'")
            .getFirst().get("steps")).isEqualTo(0L);

        // The next start (a new runner on the same database) refuses to start and says what to do.
        FirstLaunchRunner restart = new FirstLaunchRunner(environment, runs, new FirstLaunchGate(), processes,
            handlers, platformSteps, messages, clockBean, exits, json);
        assertThatThrownBy(restart::afterSingletonsInstantiated)
            .isInstanceOf(FirstLaunchRunner.FirstLaunchFailedException.class)
            .hasMessageContaining("broken")
            .hasMessageContaining("清空数据库");
        assertThat(query("SELECT kind FROM sys_first_launch_event")).extracting(row -> row.get("kind"))
            .containsExactly("FAILED");
        // Without the configuration as well: the record decides, not the settings.
        FirstLaunchRunner bare = new FirstLaunchRunner(new org.springframework.mock.env.MockEnvironment(), runs,
            new FirstLaunchGate(), processes, handlers, platformSteps, messages, clockBean, exits, json);
        assertThatThrownBy(bare::afterSingletonsInstantiated)
            .isInstanceOf(FirstLaunchRunner.FirstLaunchFailedException.class)
            .hasMessageContaining("broken");
    }
}
