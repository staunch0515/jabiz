package com.jabiz.quizbuks.it;

import com.jabiz.job.JobDefinition;
import com.jabiz.quizbuks.content.QbContent;
import com.jabiz.quizbuks.content.ai.AiCodes;
import com.jabiz.quizbuks.content.ai.AiCoverJob;
import com.jabiz.quizbuks.content.ai.AiLimits;
import com.jabiz.quizbuks.content.ai.AiProcesses;
import com.jabiz.quizbuks.content.ai.QbAi;
import com.jabiz.runtime.file.FileProcesses;
import com.jabiz.runtime.job.JobRunner;
import com.jabiz.runtime.test.StubHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * AI covers end to end against the stub (docs/quizbuks/plans/Q8-ai.md, Q8b, M-25; platform decision D50): Claude's
 * designs drawn by the server, stored by the platform as the sponsor's uploads under the content image policy
 * (recognised and re-encoded), kept while the sponsor chooses, the chosen one written into the quiz's cover through
 * the quiz's editing, the others swept; only the sponsor chooses, only from their own ready cover job; a failed job
 * stores nothing; the usage and limits are the questions'.
 */
class AiCoverIT extends AiItSupport {

    @Autowired
    @Qualifier("fileSweepJob")
    JobDefinition<FileProcesses.PurgeInput> sweep;

    @BeforeEach
    void freshDay() {
        aiOn();
        advance(Duration.ofDays(1));
    }

    /** The storage half of a sweep runs after its commit; wait for it, as the content files' tests do. */
    @AfterEach
    void awaitTheStorageSweeps() {
        await().atMost(Duration.ofSeconds(30)).until(() -> query("SELECT 1 FROM op_process p "
            + "WHERE p.process_name = 'FILE_PURGE_ORPHANS' AND NOT EXISTS (SELECT 1 FROM op_process_after_commit a "
            + "WHERE a.process_seq_id = p.process_seq_id AND a.succeeded)").isEmpty());
    }

    private String quiz(String token, String title) {
        String quizId = newQuiz(token, title);
        run("QB_QUIZ_SAVE", token, body("quizId", quizId, "title", title, "intro", "All about the Seine."));
        return quizId;
    }

    private static long revision(String quizId) {
        return ((Number) quizRow(quizId).get("revision")).longValue();
    }

    private static Map<String, Object> quizRow(String quizId) {
        return latest("qb_quiz_version", "quiz_id", "quiz_id = '" + quizId + "'").getFirst();
    }

    private static Map<String, Object> file(Object fileId) {
        List<Map<String, Object>> rows = query("SELECT * FROM sys_file WHERE file_id = ?",
            UUID.fromString(String.valueOf(fileId)));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void runSweep() {
        advance(Duration.ofDays(2));
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        assertThat(jobRunner.run(sweep, clock.instant())).isEqualTo(JobRunner.Outcome.SUCCEEDED);
    }

    @Test
    void coversAreDrawnStoredAsTheSponsorsUploadsAndOneIsChosen() {
        String token = sponsorFull(SPONSOR_A);
        String title = "Rivers of Europe " + UUID.randomUUID().toString().substring(0, 8);
        String quizId = quiz(token, title);

        Map<String, Object> requested = requestCovers(token, quizId);
        assertThat(requested).containsEntry("status", QbAi.QUEUED);
        Object aiJobId = requested.get("aiJobId");
        assertThat(job(aiJobId)).containsEntry("kind", QbAi.KIND_COVER);
        runJobs();

        Map<String, Object> job = job(aiJobId);
        assertThat(job).containsEntry("status", QbAi.READY).containsEntry("served_model", "claude-opus-5-5");
        assertThat(((Number) job.get("candidate_count")).intValue()).isEqualTo(3);
        List<Object> covers = List.of(job.get("cover_1"), job.get("cover_2"), job.get("cover_3"));
        assertThat(covers).doesNotContainNull();
        // Stored by the platform as the sponsor's uploads of the content image policy: recognised as PNG, drawn at
        // 1200 x 630, re-encoded.
        for (Object cover : covers) {
            assertThat(file(cover)).containsEntry("policy", QbContent.IMAGE_POLICY)
                .containsEntry("content_type", "image/png").containsEntry("uploaded_by", SPONSOR_A);
            assertThat(((Number) file(cover).get("width")).intValue()).isEqualTo(1200);
            assertThat(((Number) file(cover).get("height")).intValue()).isEqualTo(630);
        }
        // Which job made which file is the platform's record.
        assertThat(query("SELECT count(*) AS n FROM sys_external_job_file WHERE job_id = ? AND direction = 'OUTPUT'",
            String.valueOf(job.get("external_job_id"))).getFirst().get("n"))
            .satisfies(n -> assertThat(((Number) n).intValue()).isEqualTo(3));
        // Usage of the quiz's month and day, the questions' limits.
        assertThat(((Number) usage(SPONSOR_A).get("input_tokens")).longValue()).isPositive();
        assertThat(((Number) job.get("estimated_tokens")).longValue()).isEqualTo(
            AiLimits.estimate(((Number) job.get("input_bytes")).longValue(), AiCoverJob.MAX_TOKENS, 0));

        // What was sent: the title and introduction as data, the covers' schema with the catalogue.
        JsonNode body = sent().getLast();
        String text = userText(body);
        assertThat(text).contains("Number of covers: 3", title, "All about the Seine.");
        assertThat(documents(body)).isEmpty();
        JsonNode schema = body.get("output_config").get("format").get("schema");
        assertThat(schema.get("properties").get("covers").get("items").get("properties").get("palette")
            .get("enum").toString()).contains("ocean", "sakura");
        assertThat(body.get("max_tokens").asInt()).isEqualTo(AiCoverJob.MAX_TOKENS);
        assertThat(body.toString()).doesNotContain(SPONSOR_A);

        // The sponsor's template shows the candidates with their pictures, of this kind only.
        List<Map<String, Object>> rows = items("qb.sponsor.ai-jobs", token, body("params",
            Map.of("quizId", quizId, "kind", QbAi.KIND_COVER)));
        assertThat(rows).singleElement().satisfies(row -> assertThat(String.valueOf(row.get("candidates")))
            .contains(String.valueOf(covers.get(1)), "Cover 2"));
        assertThat(items("qb.sponsor.ai-jobs", token, body("params", Map.of("quizId", quizId, "kind",
            QbAi.KIND_QUESTIONS)))).isEmpty();
        // The sponsor may read the pictures (they are theirs).
        client.get().uri("/api/files/" + covers.get(0) + "/content").header("Authorization", token).exchange()
            .expectStatus().isOk();

        // Chosen: the quiz's cover through its editing; the quiz is AI generated.
        long before = revision(quizId);
        Map<String, Object> chosen = run(AiProcesses.COVER_ACCEPT, token, body("aiJobId", aiJobId, "quizId", quizId,
            "candidateNo", 2, "baseRevision", before));
        assertThat(((Number) chosen.get("revision")).longValue()).isEqualTo(before + 1);
        Map<String, Object> quiz = quizRow(quizId);
        assertThat(String.valueOf(quiz.get("cover"))).isEqualTo(String.valueOf(covers.get(1)));
        assertThat(quiz).containsEntry("ai_generated", true).containsEntry("changed_since_version", true);
        Map<String, Object> closed = job(aiJobId);
        assertThat(closed).containsEntry("status", QbAi.ACCEPTED).containsEntry("candidates", null)
            .containsEntry("cover_1", null).containsEntry("cover_2", null).containsEntry("cover_3", null);
        // Chosen once only.
        assertThat(codes(refused(AiProcesses.COVER_ACCEPT, token, body("aiJobId", aiJobId, "quizId", quizId,
            "candidateNo", 1), 422))).contains(AiCodes.JOB_NOT_READY);

        // The others are referred to by nothing now: the platform's sweep takes them, the chosen one stays.
        runSweep();
        assertThat(file(covers.get(0))).isNull();
        assertThat(file(covers.get(2))).isNull();
        assertThat(file(covers.get(1))).isNotNull();
    }

    @Test
    void onlyTheSponsorChoosesAndOnlyFromTheirReadyCoverJob() {
        String token = sponsorFull(SPONSOR_A);
        String quizId = quiz(token, "Cover rules " + UUID.randomUUID());
        Object aiJobId = requestCovers(token, quizId).get("aiJobId");
        // A cover job and a questions job may run on one quiz at once; a second cover job may not.
        assertThat(codes(refused(AiProcesses.COVER_REQUEST, token, body("quizId", quizId, "language", "en"), 422)))
            .contains(AiCodes.QUIZ_BUSY);
        // Not ready yet.
        assertThat(codes(refused(AiProcesses.COVER_ACCEPT, token, body("aiJobId", aiJobId, "quizId", quizId,
            "candidateNo", 1), 422))).contains(AiCodes.JOB_NOT_READY);
        runJobs();

        // Another sponsor: not found, nothing said.
        refused(AiProcesses.COVER_ACCEPT, sponsorFull(SPONSOR_B), body("aiJobId", aiJobId, "quizId", quizId,
            "candidateNo", 1), 404);
        refused(AiProcesses.DISCARD, sponsorFull(SPONSOR_B), body("aiJobId", aiJobId), 404);
        // Another of the sponsor's quizzes: not found.
        String other = quiz(token, "Other " + UUID.randomUUID());
        refused(AiProcesses.COVER_ACCEPT, token, body("aiJobId", aiJobId, "quizId", other, "candidateNo", 1), 404);
        // A cover job's candidates are no questions, and the other way round.
        assertThat(codes(refused(AiProcesses.ACCEPT, token, body("aiJobId", aiJobId, "quizId", quizId, "questions",
            List.of(body("candidateNo", 1, "stem", "Q?", "points", 5, "reviewed", true, "options",
                List.of(body("text", "a", "correct", true), body("text", "b", "correct", false))))), 422)))
            .contains(AiCodes.WRONG_KIND);
        // Out of range: 400; the quiz changed meanwhile: refused as for any edit.
        refused(AiProcesses.COVER_ACCEPT, token, body("aiJobId", aiJobId, "quizId", quizId, "candidateNo", 4), 400);
        assertThat(codes(refused(AiProcesses.COVER_ACCEPT, token, body("aiJobId", aiJobId, "quizId", quizId,
            "candidateNo", 1, "baseRevision", revision(quizId) - 1), 422))).contains("QB_QUIZ_CHANGED");
        assertThat(job(aiJobId)).containsEntry("status", QbAi.READY);

        // Discarded: closed, its pictures referred to by nothing.
        run(AiProcesses.DISCARD, token, body("aiJobId", aiJobId));
        assertThat(job(aiJobId)).containsEntry("status", QbAi.DISCARDED).containsEntry("cover_1", null);
        assertThat(quizRow(quizId)).containsEntry("cover", null).containsEntry("ai_generated", false);
    }

    @Test
    void anExpiredCoverJobLetsItsPicturesGo() {
        String token = sponsorFull(SPONSOR_A);
        String quizId = quiz(token, "Expiring " + UUID.randomUUID());
        Object aiJobId = requestCovers(token, quizId).get("aiJobId");
        runJobs();
        Object picture = job(aiJobId).get("cover_1");
        advance(Duration.ofDays(8));
        runExpiry();
        token = sponsorFull(SPONSOR_A);
        assertThat(job(aiJobId)).containsEntry("status", QbAi.EXPIRED).containsEntry("cover_1", null)
            .containsEntry("candidates", null);
        assertThat(codes(refused(AiProcesses.COVER_ACCEPT, token, body("aiJobId", aiJobId, "quizId", quizId,
            "candidateNo", 1), 422))).contains(AiCodes.JOB_NOT_READY);
        runSweep();
        assertThat(file(picture)).isNull();
    }

    @Test
    void aFailedCoverJobStoresNothing() {
        String token = sponsorFull(SPONSOR_A);
        // Refused by the provider as it is: failed at once, no picture.
        String refusedQuiz = quiz(token, "Refused " + UUID.randomUUID());
        CLAUDE.script(MESSAGES, StubHttpServer.Answer.json(400,
            "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\"}}"));
        long files = count("sys_file");
        Object failed = requestCovers(token, refusedQuiz).get("aiJobId");
        runJobs();
        assertThat(job(failed)).containsEntry("status", QbAi.FAILED).containsEntry("failure_code",
            AiCodes.REQUEST_INVALID).containsEntry("cover_1", null);
        assertThat(count("sys_file")).isEqualTo(files);

        // Designs outside the catalogue, or with texts too long: dropped; none left, the job failed, nothing stored.
        String strayQuiz = quiz(token, "Stray " + UUID.randomUUID());
        CLAUDE.script(MESSAGES, answer("claude-opus-5-5", "end_turn", JSON.writeValueAsString(Map.of("covers",
            List.of(Map.of("palette", "neon", "layout", "center", "motif", "circles", "text", "Hi"),
                Map.of("palette", "ocean", "layout", "center", "motif", "circles", "text", "x".repeat(41))))),
            100, 50));
        Object stray = requestCovers(token, strayQuiz).get("aiJobId");
        runJobs();
        assertThat(job(stray)).containsEntry("status", QbAi.FAILED).containsEntry("failure_code",
            AiCodes.NO_CANDIDATES);
        assertThat(((Number) job(stray).get("invalid_count")).intValue()).isEqualTo(2);
        assertThat(count("sys_file")).isEqualTo(files);

        // One good design of two: kept alone.
        String oneQuiz = quiz(token, "One " + UUID.randomUUID());
        CLAUDE.script(MESSAGES, answer("claude-opus-5-5", "end_turn", JSON.writeValueAsString(Map.of("covers",
            List.of(Map.of("palette", "mint", "layout", "left", "motif", "grid", "text", "Only one"),
                Map.of("palette", "ocean", "layout", "diagonal", "motif", "circles", "text", "Bad layout")))),
            100, 50));
        Object one = requestCovers(token, oneQuiz).get("aiJobId");
        runJobs();
        assertThat(job(one)).containsEntry("status", QbAi.READY).containsEntry("cover_2", null);
        assertThat(job(one).get("cover_1")).isNotNull();
        assertThat(((Number) job(one).get("candidate_count")).intValue()).isEqualTo(1);

        // A title the server's fonts cannot draw: failed before anything is sent, nothing charged.
        String undrawable = quiz(token, "Private \u0378\u0379 use");
        int calls = CLAUDE.requests(MESSAGES).size();
        Object font = requestCovers(token, undrawable).get("aiJobId");
        runJobs();
        assertThat(job(font)).containsEntry("status", QbAi.FAILED).containsEntry("failure_code", AiCodes.COVER_FONT);
        assertThat(((Number) job(font).get("input_tokens")).longValue()).isZero();
        assertThat(CLAUDE.requests(MESSAGES)).hasSize(calls);
    }

    @Test
    void coversCountAgainstTheSameLimitsAsQuestions() {
        String token = sponsorFull(SPONSOR_A);
        List<String> withArticle = quizWithArticle(token, "Both " + UUID.randomUUID(), "Paris.");
        // A cover job and a questions job of one quiz: two unfinished, the sponsor's most.
        requestCovers(token, withArticle.get(0));
        request(token, withArticle.get(0), List.of(withArticle.get(1)), 1);
        String third = quiz(token, "Third " + UUID.randomUUID());
        assertThat(codes(refused(AiProcesses.COVER_REQUEST, token, body("quizId", third, "language", "en"), 422)))
            .contains(AiCodes.TOO_MANY_OPEN);
        runJobs();
        assertThat(((Number) usage(SPONSOR_A).get("jobs")).intValue()).isGreaterThanOrEqualTo(2);

        // The AI notice and the switch hold for covers too.
        String newcomer = "cover-newcomer-" + UUID.randomUUID();
        String fresh = sponsorFull(newSponsor(newcomer));
        String theirs = quiz(fresh, "Newcomer " + UUID.randomUUID());
        assertThat(codes(refused(AiProcesses.COVER_REQUEST, fresh, body("quizId", theirs, "language", "en"), 422)))
            .contains(AiCodes.NOTICE_REQUIRED);
        // Someone else's quiz: not found.
        refused(AiProcesses.COVER_REQUEST, sponsorFull(SPONSOR_B), body("quizId", third, "language", "en",
            "aiNoticeVersionNo", noticeVersion()), 404);
        // Takers and administrators may not ask.
        refused(AiProcesses.COVER_REQUEST, taker("cover-taker-" + UUID.randomUUID()), body("quizId", third,
            "language", "en"), 403);
        refused(AiProcesses.COVER_ACCEPT, aiAdmin(), body("aiJobId", UUID.randomUUID(), "quizId", third,
            "candidateNo", 1), 403);
    }
}
