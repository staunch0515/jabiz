package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.play.AttemptFinishing;
import com.jabiz.quizbuks.play.PlayLimits;
import com.jabiz.quizbuks.publishing.PublicationMoves;
import com.jabiz.runtime.job.JobRunner;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The minute's job {@value AttemptFinishing#EXPIRE_JOB} (Q5b, U-31's server-side net; docs/quizbuks/plans/
 * Q5-answering.md, D-Q5-9): every attempt whose time and grace ran out is finished with its saved answers and settled
 * like a submission, each in a transaction of its own (platform decision D42).
 */
class AttemptExpireIT extends PlayItSupport {

    private static final Duration LIMIT = Duration.ofSeconds(60);

    /** A publication of the sponsor's open to everyone, with a one-minute time limit. */
    private String publication(String sponsor, String name) {
        return livePublication(sponsor, name, Map.of("regions", List.of("GLOBAL"), "timeLimitSec",
            (int) LIMIT.toSeconds()));
    }

    /** A new taker starts an attempt and, if {@code right}, saves the right answers to both questions. */
    private String started(String publicationId, String taker, boolean right) {
        String token = taker(newTaker(taker, null));
        String attemptId = (String) start(token, publicationId).get("attemptId");
        if (right) {
            run("QB_ATTEMPT_SAVE", token, body("attemptId", attemptId, "currentIndex", 1, "answers",
                shown(attemptId, answers(true, true))));
        }
        return attemptId;
    }

    /** Moves the clock just past the deadline and grace of attempts started {@code ago} before now. */
    private void pastTheTime(Duration ago) {
        clock.advance(LIMIT.plus(PlayLimits.SUBMIT_GRACE).minus(ago).plusSeconds(1));
    }

    private static Object status(Map<String, Object> attempt) {
        return attempt.get("status");
    }

    private static List<Object> failures(String attemptId) {
        return query("SELECT kind FROM sys_job_item_failure WHERE job_name = ? AND item_key = ? ORDER BY entry_no",
            AttemptFinishing.EXPIRE_JOB, attemptId).stream().map(row -> row.get("kind")).toList();
    }

    /** The ledger transactions of an attempt's reward. */
    private static long transactions(String attemptId) {
        return (Long) query("SELECT count(*) AS n FROM ledger_transaction_version WHERE source_entity = 'QbAttempt' "
            + "AND source_id = ?", attemptId).getFirst().get("n");
    }

    /**
     * An expiry that uses up the budget ends the publication through the move (Q10a), run by the system as the
     * expiry's sub-process.
     */
    @Test
    void anExpiryThatUsesUpTheBudgetEndsThePublicationThroughTheMove() {
        String sponsor = newSponsor("s-expire-move");
        String publicationId = livePublication(sponsor, "Expiry move", Map.of("regions", List.of("GLOBAL"),
            "timeLimitSec", (int) LIMIT.toSeconds(), "budgetCap", 150));
        credit(sponsor, 1_000);
        String attemptId = started(publicationId, "t-expire-move", true);
        pastTheTime(Duration.ZERO);
        systemRun(AttemptFinishing.EXPIRE, body("attemptId", attemptId));
        assertThat(attempt(attemptId)).containsEntry("status", "EXPIRED").containsEntry("reward_status", "PAID");
        assertThat(status(publicationId)).isEqualTo("ENDED");
        assertThat(row(publicationId)).containsEntry("end_reason", "BUDGET");
        assertThat(moves(publicationId)).isEqualTo(1);
        assertThat(query("SELECT p.process_name, c.actor_id FROM op_process c JOIN op_process p "
            + "ON p.process_seq_id = c.parent_seq_id WHERE c.process_name = ? "
            + "AND c.input_summary ->> 'publicationId' = ?", PublicationMoves.MOVE, publicationId).getFirst())
            .containsEntry("process_name", AttemptFinishing.EXPIRE).containsEntry("actor_id", "system");
        assertBooksBalance();
    }

    @Test
    void dueAttemptsAreFinishedWithTheirSavedAnswersAndSettled() {
        String sponsor = newSponsor("s-expire");
        String publicationId = publication(sponsor, "Expiry");
        credit(sponsor, 1_000);
        String full = started(publicationId, "t-expire-full", true);
        String empty = started(publicationId, "t-expire-empty", false);
        clock.advance(Duration.ofSeconds(30));
        String later = started(publicationId, "t-expire-later", true);
        pastTheTime(Duration.ofSeconds(30));

        JobRunner.JobRun run = runExpire(clock.instant());
        assertThat(run.outcome()).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        assertThat(run.items()).isEqualTo(new JobRunner.ItemCounts(2, 2, 0, 0, 0, 0, false));
        // Settled as a submission: the saved answers scored, the top tier paid from the sponsor's wallet.
        assertThat(attempt(full)).containsEntry("status", "EXPIRED").containsEntry("score", new BigDecimal("5"))
            .containsEntry("reward_status", "PAID").containsEntry("reward", new BigDecimal("100"));
        assertThat(attempt(empty)).containsEntry("status", "EXPIRED").containsEntry("score", BigDecimal.ZERO)
            .containsEntry("reward_status", "NONE");
        assertThat(status(attempt(later))).isEqualTo("IN_PROGRESS");
        assertThat(transactions(full)).isEqualTo(1);
        assertThat(balance(sponsor)).isEqualByComparingTo("900");
        assertThat(counter(publicationId)).containsEntry("completions", new BigDecimal("2"))
            .containsEntry("winners", new BigDecimal("1"));

        // The same minute again finds nothing; the next minute finishes the later one.
        assertThat(runExpire(clock.instant()).items().selected()).isZero();
        clock.advance(Duration.ofMinutes(1));
        assertThat(runExpire(clock.instant()).items().succeeded()).isEqualTo(1);
        assertThat(attempt(later)).containsEntry("status", "EXPIRED").containsEntry("reward_status", "PAID");
        assertThat(balance(sponsor)).isEqualByComparingTo("800");
        assertBooksBalance();
    }

    @Test
    void oneRunMakesUpTheMinutesMissed() {
        String sponsor = newSponsor("s-missed");
        String publicationId = publication(sponsor, "Missed");
        credit(sponsor, 1_000);
        List<String> attempts = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            attempts.add(started(publicationId, "t-missed-" + i, i % 2 == 0));
            clock.advance(Duration.ofMinutes(1));
        }
        // Down for ten minutes: one run finishes everything due (the order and the cap: AttemptExpireBatchIT).
        clock.advance(Duration.ofMinutes(10));
        JobRunner.JobRun run = runExpire(clock.instant());
        assertThat(run.items()).isEqualTo(new JobRunner.ItemCounts(3, 3, 0, 0, 0, 0, false));
        assertThat(attempts).allSatisfy(id -> assertThat(status(attempt(id))).isEqualTo("EXPIRED"));
        assertThat(balance(sponsor)).isEqualByComparingTo("800");
        assertBooksBalance();
    }

    @Test
    void anAttemptThatFailsHoldsUpNoOtherAndIsTriedAgain() {
        String sponsor = newSponsor("s-failing");
        String publicationId = publication(sponsor, "Failing");
        credit(sponsor, 1_000);
        String broken = started(publicationId, "t-failing-broken", true);
        String fine = started(publicationId, "t-failing-fine", true);
        Object order = attempt(broken).get("option_order");
        execute("UPDATE qb_attempt SET option_order = 'not a list' WHERE attempt_id = ?", broken);
        pastTheTime(Duration.ZERO);

        JobRunner.JobRun run = runExpire(clock.instant());
        assertThat(run.outcome()).isEqualTo(JobRunner.Outcome.PARTIAL);
        assertThat(run.items()).isEqualTo(new JobRunner.ItemCounts(2, 1, 0, 0, 1, 0, false));
        // Its transaction was rolled back: nothing of it stays; the other is finished and paid.
        assertThat(attempt(broken)).containsEntry("status", "IN_PROGRESS");
        assertThat(transactions(broken)).isZero();
        assertThat(attempt(fine)).containsEntry("status", "EXPIRED").containsEntry("reward_status", "PAID");
        assertThat(failures(broken)).containsExactly("FAILED");

        // Put right, it is finished by the next run (a first failure waits for no back-off) and recovers.
        execute("UPDATE qb_attempt SET option_order = ? WHERE attempt_id = ?", order, broken);
        clock.advance(Duration.ofMinutes(1));
        assertThat(runExpire(clock.instant()).items()).isEqualTo(new JobRunner.ItemCounts(1, 1, 0, 0, 0, 0, false));
        assertThat(attempt(broken)).containsEntry("status", "EXPIRED").containsEntry("reward_status", "PAID");
        assertThat(failures(broken)).containsExactly("FAILED", "RECOVERED");
        assertBooksBalance();
    }

    @Test
    void anAttemptWhosePublicationIsLockedIsLeftToTheNextMinute() throws Exception {
        String sponsor = newSponsor("s-busy");
        String busy = publication(sponsor, "Busy");
        String free = publication(sponsor, "Free");
        credit(sponsor, 1_000);
        String waiting = started(busy, "t-busy-waiting", true);
        String going = started(free, "t-busy-going", true);
        pastTheTime(Duration.ZERO);

        // Someone holds the busy publication's lock (as a long request would) for longer than an item waits.
        try (Connection holder = DB.connect(schema());
             PreparedStatement lock = holder.prepareStatement(
                 "SELECT pg_advisory_lock(hashtextextended(?, 0))")) {
            lock.setString(1, "jabiz.app-lock:" + PublicationMoves.lockName(busy));
            lock.execute();
            JobRunner.JobRun run = runExpire(clock.instant());
            assertThat(run.outcome()).isEqualTo(JobRunner.Outcome.SUCCEEDED);
            assertThat(run.items()).isEqualTo(new JobRunner.ItemCounts(2, 1, 0, 1, 0, 0, false));
            assertThat(status(attempt(waiting))).isEqualTo("IN_PROGRESS");
            assertThat(status(attempt(going))).isEqualTo("EXPIRED");
        }
        // Busy is no failure: nothing recorded, no back-off; the next minute finishes it.
        assertThat(failures(waiting)).isEmpty();
        clock.advance(Duration.ofMinutes(1));
        assertThat(runExpire(clock.instant()).items().succeeded()).isEqualTo(1);
        assertThat(attempt(waiting)).containsEntry("status", "EXPIRED").containsEntry("reward_status", "PAID");
        assertBooksBalance();
    }

    /** Runs the calls at once; returns their results in order. */
    private static <T> List<T> atOnce(List<Callable<T>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            List<T> results = new ArrayList<>();
            for (Future<T> result : pool.invokeAll(calls)) {
                results.add(result.get());
            }
            return results;
        } finally {
            pool.shutdown();
        }
    }

    private Callable<Object> submission(String token, String attemptId) {
        return () -> client.post().uri("/api/processes/QB_ATTEMPT_SUBMIT/latest")
            .contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(submission(attemptId, "answers", answers(true, true))).exchange().expectBody(MAP)
            .returnResult().getStatus().value();
    }

    /**
     * The job's items take the publication's lock, then the sponsor's wallet, as a submission does; each item commits
     * on its own, so submissions on the same publications and wallet go on while the job runs, without deadlock.
     */
    @Test
    void theJobAndSubmissionsOnOneSponsorDoNotDeadlock() throws Exception {
        warmUp();
        String sponsor = newSponsor("s-job-race");
        List<String> publications = List.of(publication(sponsor, "Job race 1"), publication(sponsor, "Job race 2"),
            publication(sponsor, "Job race 3"));
        credit(sponsor, 100_000);
        List<String> due = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            due.add(started(publications.get(i % 3), "t-job-race-due-" + i, true));
        }
        pastTheTime(Duration.ZERO);
        List<Callable<Object>> calls = new ArrayList<>();
        calls.add(() -> runExpire(clock.instant()));
        for (int i = 0; i < 12; i++) {
            String token = taker(newTaker("t-job-race-now-" + i, null));
            String attemptId = (String) start(token, publications.get(i % 3)).get("attemptId");
            calls.add(submission(token, attemptId));
        }

        List<Object> results = atOnce(calls);
        JobRunner.JobRun run = (JobRunner.JobRun) results.getFirst();
        assertThat(run.outcome()).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        assertThat(run.items().failed()).isZero();
        assertThat(run.items().succeeded() + run.items().busy()).isEqualTo(12);
        assertThat(results.subList(1, results.size())).containsOnly(200);

        // What was busy is finished by the next minutes.
        for (int minute = 0; minute < 3 && due.stream().anyMatch(id -> "IN_PROGRESS".equals(status(attempt(id))));
            minute++) {
            clock.advance(Duration.ofMinutes(1));
            assertThat(runExpire(clock.instant()).items().failed()).isZero();
        }
        assertThat(due).allSatisfy(id -> assertThat(attempt(id)).containsEntry("status", "EXPIRED")
            .containsEntry("reward_status", "PAID"));
        assertThat(balance(sponsor)).isEqualByComparingTo(BigDecimal.valueOf(100_000 - 24 * 100));
        assertBooksBalance();
    }

    /**
     * Two expiries of the same attempt at once (the job's item and another run, as another instance or a later minute
     * would start) settle it exactly once: the second, under the publication's lock, finds it finished. The taker's
     * submission sent at the same time is refused because it is past the deadline and grace, not because of the race:
     * a submission in time and the job cannot overlap by design (the job only takes attempts past the grace).
     */
    @Test
    void twoExpiriesAtOnceSettleTheAttemptOnce() throws Exception {
        warmUp();
        String sponsor = newSponsor("s-once");
        String publicationId = publication(sponsor, "Once");
        credit(sponsor, 1_000);
        String token = taker(newTaker("t-once", null));
        String attemptId = (String) start(token, publicationId).get("attemptId");
        run("QB_ATTEMPT_SAVE", token, body("attemptId", attemptId, "answers", shown(attemptId, answers(true, true))));
        pastTheTime(Duration.ZERO);

        List<Object> results = atOnce(List.of(
            () -> runExpire(clock.instant()),
            () -> systemRun(AttemptFinishing.EXPIRE, body("attemptId", attemptId)),
            submission(token, attemptId)));

        assertThat(results.get(2)).isEqualTo(422);
        assertThat(attempt(attemptId)).containsEntry("status", "EXPIRED").containsEntry("reward_status", "PAID");
        assertThat(transactions(attemptId)).isEqualTo(1);
        assertThat(counter(publicationId)).containsEntry("completions", BigDecimal.ONE)
            .containsEntry("winners", BigDecimal.ONE);
        assertThat(balance(sponsor)).isEqualByComparingTo("900");
        assertBooksBalance();
    }
}
