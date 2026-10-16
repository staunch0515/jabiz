package com.jabiz.app.it.process;

import com.jabiz.app.it.fixture.ItAdmissionFixtures;
import com.jabiz.app.it.fixture.ItAdmissionFixtures.HotInput;
import com.jabiz.app.it.fixture.ItAdmissionFixtures.HotOutput;
import com.jabiz.app.it.fixture.ItAdmissionFixtures.Mode;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.process.admission.ProcessAdmission;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The control of {@link ProcessAdmissionIT}'s first test (decision D45): the same load with the queue turned off
 * ({@code jabiz.process.admission.enabled=false}, the emergency switch). Every run of the hot key borrows a connection
 * and waits for the lock on it, the pool runs dry, and a read that has nothing to do with the lock fails with 503
 * {@code QUERY_TIMEOUT} - the failure the queue prevents, so the first test would see it if the queue did not work.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "spring.r2dbc.pool.max-size=6", "spring.r2dbc.pool.initial-size=1", "jabiz.dictionary.listen.enabled=false",
    "jabiz.process.admission.enabled=false"})
@ActiveProfiles("dev")
class ProcessAdmissionControlIT extends PostgresIntegrationTest {

    @Autowired
    ProcessExecutor executor;

    @Autowired
    ProcessAdmission admission;

    @Autowired
    ApplicationContext context;

    @AfterEach
    void cleanUp() {
        ItAdmissionFixtures.GATES.values().forEach(CountDownLatch::countDown);
        ItAdmissionFixtures.reset();
    }

    @Test
    void withoutTheQueueTheWaitersExhaustThePoolAndAReadTimesOut() throws Exception {
        WebTestClient client = WebTestClient.bindToApplicationContext(context).configureClient()
            .responseTimeout(Duration.ofSeconds(60)).build();
        String key = UUID.randomUUID().toString();
        String gate = "gate-" + UUID.randomUUID();
        ItAdmissionFixtures.GATES.put(gate, new CountDownLatch(1));
        CompletableFuture<HotOutput> holder = asTestRequest(executor.execute(ItAdmissionFixtures.HOT,
            new HotInput(key, gate, Mode.OK, "holder"))).toFuture();
        ProcessAdmissionIT.await(() -> ItAdmissionFixtures.arrived(gate) == 1, "the holder at its gate");
        List<CompletableFuture<HotOutput>> crowd = IntStream.range(0, 60).mapToObj(i -> asTestRequest(
            executor.execute(ItAdmissionFixtures.HOT, new HotInput(key, null, Mode.OK, "r" + i))).toFuture()).toList();
        // Five more connections, every one waiting for the lock: the pool (6) is empty.
        ProcessAdmissionIT.await(() -> ProcessAdmissionIT.waitingFor(ItAdmissionFixtures.FAMILY, key) == 5,
            "5 sessions waiting for the lock");
        assertThat(admission.waiting()).as("nothing queues in the application").isZero();

        String owner = "o-" + UUID.randomUUID().toString().substring(0, 8);
        execute("INSERT INTO it_ticket (f_id, f_title, f_amount, f_status, f_owner, f_created_at) VALUES"
            + " (?, 't', 1, 'OPEN', ?, now())", UUID.randomUUID().toString(), owner);
        String problem = client.post().uri("/api/queries/it.slow_titles").header("X-Jabiz-Actor", "reader")
            .header("X-Jabiz-Permissions", "*").header("Accept-Language", "en")
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("params", Map.of("owner", owner, "pause", 0)))
            .exchange().expectStatus().isEqualTo(503).expectBody(String.class).returnResult().getResponseBody();
        assertThat(problem).contains(PlatformErrorCodes.QUERY_TIMEOUT);

        ItAdmissionFixtures.GATES.get(gate).countDown();
        holder.get(30, TimeUnit.SECONDS);
        for (CompletableFuture<HotOutput> run : crowd) {
            assertThat(run.get(60, TimeUnit.SECONDS)).isNotNull();
        }
    }
}
