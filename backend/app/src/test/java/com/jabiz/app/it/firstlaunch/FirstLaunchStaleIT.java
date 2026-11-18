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
import com.jabiz.runtime.firstlaunch.FirstLaunchPowers;
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
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A run left in its content phase (the instance running it stopped): a start after the content phase's time limit
 * records it as failed and refuses to start, with or without the configuration (docs/design/23-first-launch.md
 * section 3). Two instances beginning at once are covered by the single run slot ({@code FirstLaunchIT}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "jabiz.first-launch.await-interval=50ms")
@Import(FirstLaunchStaleIT.Handler.class)
class FirstLaunchStaleIT extends PostgresIntegrationTest {

    static final AtomicBoolean HOLDING = new AtomicBoolean();
    static final List<Integer> EXITS = new CopyOnWriteArrayList<>();

    @DynamicPropertySource
    static void firstLaunch(DynamicPropertyRegistry registry) {
        registry.add("jabiz.first-launch.config", () -> FirstLaunchIT.configFile("version: 1\n"));
    }

    public record Ask() {}

    @TestConfiguration
    static class Handler {

        @Bean
        ProcessDefinition<Ask, Boolean, ProcessContext> firstLaunchItForever() {
            return ProcessDefinition.define("FL_IT_FOREVER", 1, Ask.class, Boolean.class, ProcessContext.class,
                pb -> pb
                    .permissions("test.first-launch")
                    .contextFactory((start, input) -> new ProcessContext(start))
                    .compute("Wait", (metadata, ctx) -> {
                        HOLDING.set(true);
                        ctx.put("done", false);
                    })
                    .outputMapper(ctx -> ctx.get("done", Boolean.class)));
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
                    return List.of(FirstLaunchStep.await("forever", "FL_IT_FOREVER", results -> new Ask()));
                }
            };
        }

        @Bean
        FirstLaunchExit firstLaunchItExit() {
            return EXITS::add;
        }
    }

    @Autowired
    FirstLaunchRuns runs;

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

    @Autowired
    FirstLaunchPowers powers;

    @Test
    void aRunPastItsTimeLimitIsRecordedAsFailedByTheNextStart() {
        long end = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!HOLDING.get() && System.nanoTime() < end) {
            Thread.onSpinWait();
        }
        assertThat(HOLDING).isTrue();

        // A start without the configuration whose time limit has long passed for this run.
        clock.advance(Duration.ofHours(1));
        FirstLaunchRunner later = new FirstLaunchRunner(new MockEnvironment(), runs, new FirstLaunchGate(), processes,
            handlers, platformSteps, messages, clockBean, exits, json, powers);
        assertThatThrownBy(later::afterSingletonsInstantiated)
            .isInstanceOf(FirstLaunchRunner.FirstLaunchFailedException.class)
            .hasMessageContaining("did not finish within");

        FirstLaunchRun run = runs.current()
            .contextWrite(view -> RequestContexts.put(view, RequestContext.system(Locale.ENGLISH, "it")))
            .block().orElseThrow();
        assertThat(run.state()).isEqualTo(FirstLaunchRun.State.FAILED);
        // The instance still running the content phase finds out on its next try and ends as well.
        end = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (EXITS.isEmpty() && System.nanoTime() < end) {
            Thread.onSpinWait();
        }
        assertThat(EXITS).containsExactly(1);
    }
}
