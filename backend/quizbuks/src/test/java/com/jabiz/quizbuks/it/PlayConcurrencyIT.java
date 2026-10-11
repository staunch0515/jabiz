package com.jabiz.quizbuks.it;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The locks under load (docs/quizbuks/plans/Q5-answering.md, D-Q5-5, D-Q5-7): one sponsor's wallet paid from two
 * publications at once never goes below 0, a pause wins against starts, and removing a quiz does not deadlock with
 * answering it.
 */
class PlayConcurrencyIT extends PlayItSupport {

    /** Runs the calls at once; returns each call's HTTP status. */
    List<Integer> atOnce(List<Callable<Integer>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : pool.invokeAll(calls)) {
                statuses.add(result.get());
            }
            return statuses;
        } finally {
            pool.shutdown();
        }
    }

    Callable<Integer> call(String process, String token, Map<String, Object> body) {
        return () -> client.post().uri("/api/processes/" + process + "/latest").contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, token).bodyValue(body).exchange().expectBody(MAP).returnResult()
            .getStatus().value();
    }

    @Test
    void aWalletThatPaysFiveOfFortyPaysExactlyFive() throws Exception {
        warmUp();
        String sponsor = newSponsor("s-race");
        String first = livePublication(sponsor, "Race one", Map.of("regions", List.of("GLOBAL")));
        String second = livePublication(sponsor, "Race two", Map.of("regions", List.of("GLOBAL")));
        credit(sponsor, 500);
        List<Callable<Integer>> submissions = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            String token = taker(newTaker("t-race-" + i, null));
            String attemptId = (String) start(token, i % 2 == 0 ? first : second).get("attemptId");
            submissions.add(call("QB_ATTEMPT_SUBMIT", token, body("attemptId", attemptId, "answers",
                answers(true, true))));
        }
        assertThat(atOnce(submissions)).containsOnly(200);
        assertThat(query("SELECT reward_status, count(*) AS n FROM qb_attempt WHERE sponsor_id = ? "
            + "GROUP BY reward_status ORDER BY reward_status", sponsor))
            .containsExactly(Map.of("reward_status", "PAID", "n", 5L), Map.of("reward_status", "PENDING", "n", 35L));
        assertThat(balance(sponsor)).isZero();
        assertThat(wallet(sponsor)).containsEntry("pending_count", new BigDecimal("35"))
            .containsEntry("pending_amount", new BigDecimal("3500"));
        assertThat(List.of(status(first), status(second))).containsOnly("FUNDS_SHORT");
        assertBooksBalance();
    }

    @Test
    void noStartGetsInAfterAPause() throws Exception {
        warmUp();
        String sponsor = newSponsor("s-pause-race");
        String publicationId = livePublication(sponsor, "Pause race", Map.of("regions", List.of("GLOBAL")));
        credit(sponsor, 10_000);
        List<Callable<Integer>> calls = new ArrayList<>();
        calls.add(call("QB_PUBLICATION_PAUSE", sponsorToken(sponsor), body("publicationId", publicationId)));
        for (int i = 0; i < 12; i++) {
            calls.add(call("QB_ATTEMPT_START", taker(newTaker("t-pause-race-" + i, null)),
                body("publicationId", publicationId)));
        }
        List<Integer> statuses = atOnce(calls);
        assertThat(statuses.getFirst()).isEqualTo(200);
        assertThat(statuses).containsOnly(200, 422);
        // Every start that got in is counted, and nothing gets in after the pause.
        long started = statuses.subList(1, statuses.size()).stream().filter(s -> s == 200).count();
        assertThat(counter(publicationId).get("starts")).isEqualTo(BigDecimal.valueOf(started));
        assertThat(query("SELECT count(*) AS n FROM qb_attempt WHERE publication_id = ?::uuid", publicationId)
            .getFirst()).containsEntry("n", started);
        assertThat(refused("QB_ATTEMPT_START", taker(newTaker("t-pause-race-late", null)),
            body("publicationId", publicationId), 422)).isNotNull();
    }

    @Test
    void removingTheQuizWhileTakersStartDoesNotDeadlock() throws Exception {
        warmUp();
        String sponsor = newSponsor("s-remove-race");
        String publicationId = livePublication(sponsor, "Remove race", Map.of("regions", List.of("GLOBAL")));
        credit(sponsor, 10_000);
        String quizId = String.valueOf(query("SELECT quiz_id FROM qb_publication_schedule WHERE publication_id = ?::uuid",
            publicationId).getFirst().get("quiz_id"));
        List<Callable<Integer>> calls = new ArrayList<>();
        calls.add(call("QB_QUIZ_DELETE", sponsorToken(sponsor), body("quizId", quizId)));
        for (int i = 0; i < 12; i++) {
            calls.add(call("QB_ATTEMPT_START", taker(newTaker("t-remove-race-" + i, null)),
                body("publicationId", publicationId)));
        }
        List<Integer> statuses = atOnce(calls);
        // No deadlock (409) and no failure: the removal and every start went through.
        assertThat(statuses).containsOnly(200);
        // The version stays answerable: its files are kept for the publication (Q4).
        Map<String, Object> attempt = query("SELECT attempt_id, user_id FROM qb_attempt WHERE publication_id = "
            + "?::uuid LIMIT 1", publicationId).getFirst();
        assertThat(submit(taker((String) attempt.get("user_id")), (String) attempt.get("attempt_id"),
            answers(true, true))).containsEntry("rewardStatus", "PAID");
    }
}
