package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.identity.SponsorErasure;
import com.jabiz.quizbuks.setup.QbParams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M15c (docs/quizbuks/plans/impl/F-M15.md §5.5): {@code QB_SPONSOR_ERASED} cancels the erased sponsor's AI jobs still
 * open. QuizBuks' own job rows are deleted with the account, so the platform's jobs are found by who asked for them,
 * with no time window: a job queued while {@code qb.ai.enabled} is off waits without using attempts, however long, and
 * is cancelled all the same. It never reaches the provider once the switch is on again, and nothing is charged (its
 * outcome process finds no row). A job that ended (succeeded) is left alone.
 */
class AiJobsErasedIT extends AiItSupport {

    @BeforeEach
    void freshDay() {
        aiOn();
        advance(Duration.ofDays(1));
    }

    @AfterEach
    void aiBackOn() {
        aiOn();
    }

    private static List<Object> cancelledBy(Object externalJobId) {
        return query("SELECT cancelled_by FROM sys_external_job_cancel WHERE job_id = ?", String.valueOf(externalJobId))
            .stream().map(row -> row.get("cancelled_by")).toList();
    }

    @Test
    void aJobQueuedWhileTheSwitchIsOffIsCancelledOnTheErasureAndNeverReachesTheProvider() {
        String sponsor = newSponsor("ai-erased-" + UUID.randomUUID());
        String token = sponsorFull(sponsor);
        // One job done before (succeeded): not cancelled.
        List<String> done = quizWithArticle(token, "Done " + UUID.randomUUID(), "Material.");
        Map<String, Object> finished = request(token, done.get(0), List.of(done.get(1)), 1);
        runJobs();
        // One job asked for, then the switch off: it waits, held, without using an attempt.
        List<String> quiz = quizWithArticle(token, "Queued " + UUID.randomUUID(), "Material.");
        Map<String, Object> queued = request(token, quiz.get(0), List.of(quiz.get(1)), 1);
        param(QbParams.AI_ENABLED, "false");
        runJobs();
        assertThat(platformAttempts(queued.get("externalJobId"))).isEmpty();
        int calls = CLAUDE.requests(MESSAGES).size();
        List<Map<String, Object>> usage = query("SELECT jobs, failed_jobs, input_tokens, output_tokens FROM qb_ai_usage "
            + "WHERE sponsor_id = ?", sponsor);

        runAsSystem("ACCOUNT_ERASE", Map.of("userId", sponsor, "requestedBy", "it-support"));
        assertThat(query("SELECT 1 FROM qb_ai_job WHERE sponsor_id = ?", sponsor)).as("deleted with the account")
            .isEmpty();
        systemRun(SponsorErasure.ERASED, body("userId", sponsor));

        assertThat(cancelledBy(queued.get("externalJobId"))).hasSize(1);
        assertThat(cancelledBy(finished.get("externalJobId"))).isEmpty();
        // Again (the event delivered twice; the wind-up repeats it too): nothing more, no error.
        systemRun(SponsorErasure.ERASED, body("userId", sponsor));
        assertThat(cancelledBy(queued.get("externalJobId"))).hasSize(1);

        aiOn();
        runJobs();
        assertThat(CLAUDE.requests(MESSAGES)).hasSize(calls);
        assertThat(platformAttempts(queued.get("externalJobId"))).isEmpty();
        // Nothing charged for it: the usage is what the finished job left.
        assertThat(query("SELECT jobs, failed_jobs, input_tokens, output_tokens FROM qb_ai_usage WHERE sponsor_id = ?",
            sponsor)).isEqualTo(usage);
    }
}
