package com.jabiz.quizbuks.it;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The baseline of one hot publication (docs/quizbuks/plans/Q5-answering.md, D-Q5-10): 64 clients at once each start and
 * submit an attempt of the same publication, every reward paid from one sponsor's wallet; completions a second and the
 * p50 / p95 of starts and submissions are printed. Not part of {@code check} (tag {@code perf}): run it with
 * {@code ./gradlew :quizbuks:perfTest}. In-process (no network), on the test database, with BlockHound on: a lower
 * bound for Q10, not its measurement.
 */
@Tag("perf")
class PlayThroughputIT extends PlayItSupport {

    static final int CLIENTS = 64;

    @Test
    void oneHotPublication() throws Exception {
        warmUp();
        String sponsor = newSponsor("s-perf");
        String publicationId = livePublication(sponsor, "Hot", Map.of("regions", List.of("GLOBAL"), "maxWinners",
            100_000, "budgetCap", 10_000_000));
        credit(sponsor, 1_000_000);
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < CLIENTS; i++) {
            tokens.add(taker(newTaker("t-perf-" + i, null)));
        }
        List<Long> starts = Collections.synchronizedList(new ArrayList<>());
        List<Long> submits = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(CLIENTS);
        long began = System.nanoTime();
        try {
            List<Callable<Boolean>> calls = new ArrayList<>();
            for (String token : tokens) {
                calls.add(() -> {
                    long t0 = System.nanoTime();
                    var started = client.post().uri("/api/processes/QB_ATTEMPT_START/latest")
                        .contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, token)
                        .bodyValue(body("publicationId", publicationId)).exchange().expectBody(MAP).returnResult();
                    long t1 = System.nanoTime();
                    starts.add(t1 - t0);
                    @SuppressWarnings("unchecked")
                    String attemptId = (String) ((Map<String, Object>) started.getResponseBody().get("output"))
                        .get("attemptId");
                    var submitted = client.post().uri("/api/processes/QB_ATTEMPT_SUBMIT/latest")
                        .contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, token)
                        .bodyValue(body("attemptId", attemptId, "answers", answers(true, true))).exchange()
                        .expectBody(MAP).returnResult();
                    submits.add(System.nanoTime() - t1);
                    return submitted.getStatus().value() == 200;
                });
            }
            for (Future<Boolean> result : pool.invokeAll(calls)) {
                assertThat(result.get()).isTrue();
            }
        } finally {
            pool.shutdown();
        }
        double seconds = (System.nanoTime() - began) / 1e9;
        System.out.printf("PlayThroughputIT: %d clients, %.1f completions/s; start p50 %d ms p95 %d ms; "
                + "submit p50 %d ms p95 %d ms%n", CLIENTS, CLIENTS / seconds, percentile(starts, 50),
            percentile(starts, 95), percentile(submits, 50), percentile(submits, 95));
        assertBooksBalance();
    }

    static long percentile(List<Long> nanos, int p) {
        List<Long> sorted = new ArrayList<>(nanos);
        Collections.sort(sorted);
        int index = Math.min(sorted.size() - 1, (int) Math.ceil(p / 100.0 * sorted.size()) - 1);
        return sorted.get(Math.max(0, index)) / 1_000_000;
    }
}
