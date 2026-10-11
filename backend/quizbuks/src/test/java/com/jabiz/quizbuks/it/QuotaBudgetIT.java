package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.play.PlayCodes;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Winners and budget (docs/quizbuks/plans/Q5-answering.md, D-Q5-3): attempts in progress hold a winner's place and
 * the highest reward each, so neither limit is ever exceeded, and a publication ends once either is used up.
 */
class QuotaBudgetIT extends PlayItSupport {

    String startFor(String publicationId, String name) {
        return (String) start(taker(newTaker(name, null)), publicationId).get("attemptId");
    }

    Map<String, Object> finish(String attemptId, boolean first, boolean second) {
        clock.advance(Duration.ofSeconds(1));
        return submit(taker((String) attempt(attemptId).get("user_id")), attemptId, answers(first, second));
    }

    Map<String, Object> refusedStart(String publicationId, String name) {
        return refused("QB_ATTEMPT_START", taker(newTaker(name, null)), body("publicationId", publicationId), 422);
    }

    @Test
    void winnersPlacesAreHeldWhileInProgressAndTheLastWinnerEndsIt() {
        String sponsor = newSponsor("s-quota");
        String publicationId = livePublication(sponsor, "Two winners", Map.of("regions", List.of("GLOBAL"),
            "maxWinners", 2));
        credit(sponsor, 1_000);
        String a = startFor(publicationId, "t-quota-a");
        String b = startFor(publicationId, "t-quota-b");
        assertThat(codes(refusedStart(publicationId, "t-quota-c"))).containsExactly(PlayCodes.QUOTA_FULL);
        // A completion without a reward frees its place.
        assertThat(finish(a, false, false)).containsEntry("rewardStatus", "NONE");
        String c = startFor(publicationId, "t-quota-c");
        finish(b, true, true);
        assertThat(status(publicationId)).isEqualTo("LIVE");
        finish(c, false, true);
        assertThat(status(publicationId)).isEqualTo("ENDED");
        assertThat(row(publicationId)).containsEntry("end_reason", "QUOTA");
        assertThat(card(publicationId)).containsEntry("status", "ENDED");
        assertThat(counter(publicationId)).containsEntry("starts", new BigDecimal("3"))
            .containsEntry("completions", new BigDecimal("3")).containsEntry("winners", new BigDecimal("2"))
            .containsEntry("reserved", BigDecimal.ZERO).containsEntry("spent", new BigDecimal("110"));
        assertThat(codes(refusedStart(publicationId, "t-quota-d"))).containsExactly(PlayCodes.NOT_OPEN);
    }

    @Test
    void theBudgetIsReservedAndEndsThePublicationWhenItCannotPayAnotherTopReward() {
        String sponsor = newSponsor("s-budget");
        String publicationId = livePublication(sponsor, "Small budget", Map.of("regions", List.of("GLOBAL"),
            "budgetCap", 250));
        credit(sponsor, 1_000);
        String a = startFor(publicationId, "t-budget-a");
        String b = startFor(publicationId, "t-budget-b");
        // 200 reserved: another 100 would exceed 250.
        assertThat(codes(refusedStart(publicationId, "t-budget-c"))).containsExactly(PlayCodes.BUDGET_FULL);
        finish(a, true, true);
        assertThat(status(publicationId)).isEqualTo("LIVE");
        assertThat(codes(refusedStart(publicationId, "t-budget-d"))).containsExactly(PlayCodes.BUDGET_FULL);
        // In progress when the budget runs low, b still completes and is paid.
        assertThat(finish(b, true, true)).containsEntry("rewardStatus", "PAID");
        assertThat(status(publicationId)).isEqualTo("ENDED");
        assertThat(row(publicationId)).containsEntry("end_reason", "BUDGET");
        assertThat(counter(publicationId)).containsEntry("spent", new BigDecimal("200"))
            .containsEntry("reserved", BigDecimal.ZERO);
        assertBooksBalance();
    }

    @Test
    void manyTakersAtOnceNeverExceedTheLimits() throws Exception {
        warmUp();
        String sponsor = newSponsor("s-crowd");
        int winnersCap = 10;
        String publicationId = livePublication(sponsor, "Crowd", Map.of("regions", List.of("GLOBAL"),
            "maxWinners", winnersCap, "budgetCap", 100_000));
        credit(sponsor, 100_000);
        int threads = 32;
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tokens.add(taker(newTaker("t-crowd-" + i, null)));
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Integer>> calls = new ArrayList<>();
            for (String token : tokens) {
                calls.add(() -> {
                    var started = client.post().uri("/api/processes/QB_ATTEMPT_START/latest")
                        .contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, token)
                        .bodyValue(body("publicationId", publicationId)).exchange().expectBody(MAP).returnResult();
                    if (started.getStatus().value() != 200) {
                        assertThat(started.getStatus().value()).as(String.valueOf(started.getResponseBody()))
                            .isEqualTo(422);
                        return 0;
                    }
                    @SuppressWarnings("unchecked")
                    String attemptId = (String) ((Map<String, Object>) started.getResponseBody().get("output"))
                        .get("attemptId");
                    var submitted = client.post().uri("/api/processes/QB_ATTEMPT_SUBMIT/latest")
                        .contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, token)
                        .bodyValue(body("attemptId", attemptId, "answers", answers(true, true))).exchange()
                        .expectBody(MAP).returnResult();
                    assertThat(submitted.getStatus().value()).as(String.valueOf(submitted.getResponseBody()))
                        .isEqualTo(200);
                    return 1;
                });
            }
            int completed = 0;
            for (Future<Integer> result : pool.invokeAll(calls)) {
                completed += result.get();
            }
            assertThat(completed).isBetween(winnersCap, threads);
        } finally {
            pool.shutdown();
        }
        Map<String, Object> counter = counter(publicationId);
        long winners = ((BigDecimal) counter.get("winners")).longValue();
        assertThat(winners).isLessThanOrEqualTo(winnersCap);
        Map<String, Object> actual = query("SELECT count(*) AS starts, count(*) FILTER (WHERE status <> "
            + "'IN_PROGRESS') AS completions, count(*) FILTER (WHERE reward > 0) AS winners, coalesce(sum(reward), 0) "
            + "AS spent FROM qb_attempt WHERE publication_id = ?::uuid", publicationId).getFirst();
        assertThat(((BigDecimal) counter.get("starts")).longValue()).isEqualTo(actual.get("starts"));
        assertThat(((BigDecimal) counter.get("completions")).longValue()).isEqualTo(actual.get("completions"));
        assertThat(winners).isEqualTo(actual.get("winners"));
        assertThat((BigDecimal) counter.get("spent")).isEqualByComparingTo((BigDecimal) actual.get("spent"));
        assertThat((BigDecimal) counter.get("reserved")).isZero();
        assertThat(winners).isEqualTo(winnersCap);
        assertThat(status(publicationId)).isEqualTo("ENDED");
        assertBooksBalance();
    }
}
