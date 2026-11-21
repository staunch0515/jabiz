package com.jabiz.quizbuks.it;

import com.jabiz.runtime.firstlaunch.FirstLaunchExit;
import com.jabiz.runtime.firstlaunch.FirstLaunchGate;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-LAUNCH plan section 9, item 19: a step of the content phase that fails ends the run. The example's second sponsor
 * asks for the page address the first one took, which only its import finds out: the run is recorded FAILED with the
 * step and the reason, the application exits with a code that is not 0 and does not open, and nothing after the
 * step was run.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "jabiz.first-launch.await-interval=100ms", "jabiz.events.delivery.enabled=true", "jabiz.mail.enabled=true",
    "jabiz.mail.from=quizbuks@example.com", "jabiz.mail.base-url=https://app.quizbuks.test",
    "spring.mail.host=localhost", "spring.mail.port=3043"})
@Import(QbFirstLaunchFailureIT.Exits.class)
class QbFirstLaunchFailureIT extends PostgresIntegrationTest {

    static final List<Integer> EXITS = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class Exits {
        @Bean
        FirstLaunchExit qbFirstLaunchFailureItExit() {
            return EXITS::add;
        }
    }

    @DynamicPropertySource
    static void firstLaunch(DynamicPropertyRegistry registry) {
        // Both sponsors ask for the same page address.
        registry.add("jabiz.first-launch.config", () -> QbFirstLaunchIT.example("tokyo-direct"));
    }

    @AfterAll
    static void cleanUp() throws IOException {
        QbFirstLaunchIT.cleanUp();
    }

    @Autowired
    FirstLaunchGate gate;

    @Test
    void aFailedStepEndsTheRunAndTheApplicationExits() throws InterruptedException {
        long end = System.nanoTime() + Duration.ofMinutes(5).toNanos();
        while (EXITS.isEmpty() && System.nanoTime() < end) {
            Thread.sleep(100);
        }
        assertThat(EXITS).as("the application exits").isNotEmpty().allMatch(code -> code != 0);
        assertThat(gate.isOpen()).isFalse();

        List<Map<String, Object>> events = query("SELECT kind, step_key, reason FROM sys_first_launch_event");
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.get("kind")).isEqualTo("FAILED");
            assertThat(String.valueOf(event.get("step_key"))).startsWith("qb:kansai:import:");
            assertThat(String.valueOf(event.get("reason"))).isNotBlank();
        });
        // The first sponsor's content was imported; nothing of the second sponsor's page or later steps ran.
        assertThat(query("SELECT 1 FROM qb_sponsor_page")).hasSize(1);
        assertThat(query("SELECT 1 FROM op_process WHERE process_name = 'QB_FIRST_LAUNCH_APPROVE'")).isEmpty();
    }
}
