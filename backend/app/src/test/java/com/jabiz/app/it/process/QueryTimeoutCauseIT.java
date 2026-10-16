package com.jabiz.app.it.process;

import com.jabiz.app.it.fixture.ItAdmissionFixtures;
import com.jabiz.app.it.fixture.ItAdmissionFixtures.HotInput;
import com.jabiz.app.it.fixture.ItAdmissionFixtures.HotOutput;
import com.jabiz.app.it.fixture.ItAdmissionFixtures.Mode;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.runtime.approval.ApprovalEntities;
import com.jabiz.runtime.observability.PlatformObservations;
import com.jabiz.runtime.process.ExecutionOptions;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.process.ProcessResult;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where the 503 {@code QUERY_TIMEOUT} of an overloaded process API comes from (QuizBuks Q10a; decision D45 item 6,
 * written down in docs/design/03-dataset.md section 1): with the pool's connections all taken, a request of a process
 * whose caller does not hold {@code *} reads the segregation-of-duties rules at the entry - a dataset query outside
 * any transaction, whose time limit (5 s, plus the client's 1 s) counts the wait for a connection too - and fails
 * after about 6 seconds. The replay lookup of an {@code Idempotency-Key} has no time limit: it waits for a connection
 * as long as it takes, so it is not the source. The pool has 2 connections here, both held by the test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "spring.r2dbc.pool.max-size=2", "spring.r2dbc.pool.initial-size=1", "jabiz.dictionary.listen.enabled=false"})
@ActiveProfiles("dev")
class QueryTimeoutCauseIT extends PostgresIntegrationTest {

    /** One stopped observation: its name and tags. */
    record Seen(String name, Map<String, String> tags) {}

    static final List<Seen> SEEN = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class Recording {
        @Bean
        ObservationHandler<Observation.Context> recordingHandler() {
            return new ObservationHandler<>() {
                @Override
                public void onStop(Observation.Context context) {
                    Map<String, String> tags = new TreeMap<>();
                    for (KeyValue kv : context.getLowCardinalityKeyValues()) {
                        tags.put(kv.getKey(), kv.getValue());
                    }
                    SEEN.add(new Seen(context.getName(), tags));
                }

                @Override
                public boolean supportsContext(Observation.Context context) {
                    return true;
                }
            };
        }
    }

    @Autowired
    ApplicationContext context;

    @Autowired
    ConnectionFactory connections;

    @Autowired
    ProcessExecutor executor;

    private final List<Connection> held = new ArrayList<>();

    @AfterEach
    void release() {
        held.forEach(connection -> Mono.from(connection.close()).block(Duration.ofSeconds(10)));
        held.clear();
    }

    private void holdTheWholePool() {
        for (int i = 0; i < 2; i++) {
            held.add(Mono.from(connections.create()).block(Duration.ofSeconds(10)));
        }
    }

    @Test
    void theEntrysReadOfTheSodRulesTimesOutWaitingForAConnection() {
        WebTestClient client = WebTestClient.bindToApplicationContext(context).configureClient()
            .responseTimeout(Duration.ofSeconds(60)).build();
        // Warm: the entry and its read work while the pool has connections.
        run(client).expectStatus().isOk();
        holdTheWholePool();
        SEEN.clear();

        long started = System.nanoTime();
        String problem = run(client).expectStatus().isEqualTo(503).expectBody(String.class).returnResult()
            .getResponseBody();
        Duration took = Duration.ofNanos(System.nanoTime() - started);
        assertThat(problem).contains(PlatformErrorCodes.QUERY_TIMEOUT);
        // The dataset's 5 s and the client's 1 s more.
        assertThat(took).isBetween(Duration.ofMillis(5500), Duration.ofSeconds(9));
        assertThat(SEEN).as("the read that timed out: the SoD rules, outside the process")
            .anySatisfy(seen -> {
                assertThat(seen.name()).isEqualTo(PlatformObservations.DATASET_QUERY);
                assertThat(seen.tags()).containsEntry("dataset", ApprovalEntities.SOD_RULE_DATASET)
                    .containsEntry("status", "503");
            });
        assertThat(SEEN).as("the process itself never started")
            .noneMatch(seen -> seen.name().equals(PlatformObservations.PROCESS));
    }

    @Test
    void theReplayLookupWaitsForAConnectionWithoutATimeLimit() throws Exception {
        // Warm, so that nothing but the lookup needs a connection first.
        asTestRequest(executor.execute(ItAdmissionFixtures.PLAIN, new HotInput(null, null, Mode.OK, "warm")))
            .block(Duration.ofSeconds(10));
        holdTheWholePool();
        CompletableFuture<ProcessResult<HotOutput>> replay = asTestRequest(executor.run(ItAdmissionFixtures.PLAIN,
            new HotInput(null, null, Mode.OK, "replay"), ExecutionOptions.idempotent("cause-" + UUID.randomUUID())))
            .toFuture();
        // Longer than any read's limit: still waiting, not failed.
        Thread.sleep(8000);
        assertThat(replay).isNotDone();
        release();
        assertThat(replay.get(10, TimeUnit.SECONDS).output().tag()).isEqualTo("replay");
    }

    private static WebTestClient.ResponseSpec run(WebTestClient client) {
        // Not "*": the entry reads the SoD rules (a holder of "*" is not checked there).
        return client.post().uri("/api/processes/" + ItAdmissionFixtures.PLAIN_PROCESS + "/latest")
            .header("X-Jabiz-Actor", "cause").header("X-Jabiz-Permissions", ItAdmissionFixtures.PERMISSION)
            .header("Accept-Language", "en").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("tag", "cause")).exchange();
    }
}
