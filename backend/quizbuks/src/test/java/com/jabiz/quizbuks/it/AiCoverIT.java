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
 * AI covers end to end against the stub (docs/quizbuks/plans/Q8-ai.md, Q8b, M-25; Q96; platform decision D50): one
 * cover, an illustration Claude wrote as SVG or a design of the catalogue, drawn by the server, stored by the platform
 * as the sponsor's upload under the content image policy (recognised and re-encoded), kept while the sponsor decides,
 * used through the quiz's editing or closed by asking again, and swept when not used; only the sponsor uses it, only
 * from their own ready cover job; a failed job stores nothing; the usage and limits are the questions'.
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

    private void runUntilDone(Object aiJobId) {
        for (int i = 0; i < 6 && job(aiJobId).get("finished_at") == null; i++) {
            runJobs();
            advance(Duration.ofMinutes(3));
        }
    }

    private Map<String, Object> requestCover(String token, String quizId, Object... more) {
        Map<String, Object> input = new java.util.LinkedHashMap<>(body("quizId", quizId, "language", "en",
            "aiNoticeVersionNo", noticeVersion()));
        input.putAll(body(more));
        return run(AiProcesses.COVER_REQUEST, token, input);
    }

    @Test
    void anIllustrationIsDrawnStoredAsTheSponsorsUploadAndUsed() {
        String token = sponsorFull(SPONSOR_A);
        String title = "Rivers of Europe " + UUID.randomUUID().toString().substring(0, 8);
        String quizId = quiz(token, title);

        Map<String, Object> requested = requestCover(token, quizId, "requirements", "A river at dawn, blue tones");
        assertThat(requested).containsEntry("status", QbAi.QUEUED);
        Object aiJobId = requested.get("aiJobId");
        assertThat(job(aiJobId)).containsEntry("kind", QbAi.KIND_COVER);
        runJobs();

        Map<String, Object> job = job(aiJobId);
        assertThat(job).containsEntry("status", QbAi.READY).containsEntry("served_model", "claude-opus-5-5");
        assertThat(((Number) job.get("candidate_count")).intValue()).isEqualTo(1);
        Object cover = job.get("cover_1");
        assertThat(cover).isNotNull();
        assertThat(job).containsEntry("cover_2", null).containsEntry("cover_3", null);
        // Stored by the platform as the sponsor's upload of the content image policy: recognised as PNG, drawn at
        // 1200 x 630, re-encoded.
        assertThat(file(cover)).containsEntry("policy", QbContent.IMAGE_POLICY)
            .containsEntry("content_type", "image/png").containsEntry("uploaded_by", SPONSOR_A);
        assertThat(((Number) file(cover).get("width")).intValue()).isEqualTo(1200);
        assertThat(((Number) file(cover).get("height")).intValue()).isEqualTo(630);
        assertThat(((Number) job.get("estimated_tokens")).longValue()).isEqualTo(AiLimits.estimate(
            ((Number) job.get("input_bytes")).longValue(), AiCoverJob.ILLUSTRATION_MAX_TOKENS, 0));
        assertThat(String.valueOf(job.get("params"))).contains("\"style\":\"ILLUSTRATION\"",
            "\"requirements\":\"A river at dawn, blue tones\"", "\"titleOverlay\":true");

        // What was sent: the illustration's prompt and schema, the requirements as data, little thinking.
        JsonNode body = sent().getLast();
        String text = userText(body);
        assertThat(text).contains("Style: illustration", "Title on the picture: yes", title, "All about the Seine.",
            "A river at dawn, blue tones");
        assertThat(body.get("max_tokens").asInt()).isEqualTo(AiCoverJob.ILLUSTRATION_MAX_TOKENS);
        assertThat(body.get("output_config").get("effort").asString()).isEqualTo(AiCoverJob.ILLUSTRATION_EFFORT);
        assertThat(body.get("output_config").get("format").get("schema").get("properties").has("svg")).isTrue();
        assertThat(body.toString()).doesNotContain(SPONSOR_A);

        // The sponsor's template shows the one candidate with its picture and title.
        List<Map<String, Object>> rows = items("qb.sponsor.ai-jobs", token, body("params",
            Map.of("quizId", quizId, "kind", QbAi.KIND_COVER)));
        assertThat(rows).singleElement().satisfies(row -> assertThat(String.valueOf(row.get("candidates")))
            .contains(String.valueOf(cover), "ILLUSTRATION", "Cover", "bottom"));
        client.get().uri("/api/files/" + cover + "/content").header("Authorization", token).exchange()
            .expectStatus().isOk();

        // Used: the quiz's cover through its editing; the quiz is AI generated. There is no second candidate.
        refused(AiProcesses.COVER_ACCEPT, token, body("aiJobId", aiJobId, "quizId", quizId, "candidateNo", 2), 400);
        long before = revision(quizId);
        Map<String, Object> chosen = run(AiProcesses.COVER_ACCEPT, token, body("aiJobId", aiJobId, "quizId", quizId,
            "candidateNo", 1, "baseRevision", before));
        assertThat(((Number) chosen.get("revision")).longValue()).isEqualTo(before + 1);
        Map<String, Object> quiz = quizRow(quizId);
        assertThat(String.valueOf(quiz.get("cover"))).isEqualTo(String.valueOf(cover));
        assertThat(quiz).containsEntry("ai_generated", true).containsEntry("changed_since_version", true);
        assertThat(job(aiJobId)).containsEntry("status", QbAi.ACCEPTED).containsEntry("candidates", null)
            .containsEntry("cover_1", null);
        runSweep();
        assertThat(file(cover)).isNotNull();
    }

    @Test
    void askingAgainClosesTheCoverNotUsed() {
        String token = sponsorFull(SPONSOR_A);
        String quizId = quiz(token, "Again " + UUID.randomUUID());
        Object first = requestCover(token, quizId).get("aiJobId");
        runJobs();
        Object firstPicture = job(first).get("cover_1");
        assertThat(job(first)).containsEntry("status", QbAi.READY);

        // Refused (the cover text is too long): the first one stays to be used.
        assertThat(codes(refused(AiProcesses.COVER_REQUEST, token, body("quizId", quizId, "language", "en",
            "titleText", "x".repeat(41)), 422))).contains(AiCodes.COVER_TEXT);
        assertThat(job(first)).containsEntry("status", QbAi.READY);

        // Asked again with other requirements: the first is closed in the same request, its picture let go.
        Object second = requestCover(token, quizId, "requirements", "Warmer colours", "titleOverlay", false).get(
            "aiJobId");
        assertThat(job(first)).containsEntry("status", QbAi.DISCARDED).containsEntry("cover_1", null)
            .containsEntry("candidates", null);
        assertThat(userText(sent().getLast())).doesNotContain("Warmer colours");
        runJobs();
        assertThat(job(second)).containsEntry("status", QbAi.READY);
        assertThat(userText(sent().getLast())).contains("Warmer colours", "Title on the picture: none");
        assertThat(String.valueOf(job(second).get("candidates"))).contains("\"text\":null");
        // Each request counts against the day's and the month's limits.
        assertThat(((Number) usage(SPONSOR_A).get("jobs")).intValue()).isGreaterThanOrEqualTo(2);
        assertThatTheFirstIsGone(firstPicture, job(second).get("cover_1"));
    }

    private void assertThatTheFirstIsGone(Object firstPicture, Object secondPicture) {
        runSweep();
        assertThat(file(firstPicture)).isNull();
        assertThat(file(secondPicture)).isNotNull();
    }

    @Test
    void theCatalogueStyleIsOneDesignWithTheSponsorsText() {
        String token = sponsorFull(SPONSOR_A);
        String quizId = quiz(token, "Graphic " + UUID.randomUUID());
        Object aiJobId = requestCover(token, quizId, "style", "GRAPHIC", "requirements", "Green and calm",
            "titleText", "My own cover").get("aiJobId");
        runJobs();
        Map<String, Object> job = job(aiJobId);
        assertThat(job).containsEntry("status", QbAi.READY);
        assertThat(((Number) job.get("candidate_count")).intValue()).isEqualTo(1);
        assertThat(String.valueOf(job.get("candidates"))).contains("GRAPHIC", "My own cover", "ocean");
        assertThat(((Number) job.get("estimated_tokens")).longValue()).isEqualTo(AiLimits.estimate(
            ((Number) job.get("input_bytes")).longValue(), AiCoverJob.MAX_TOKENS, 0));
        JsonNode body = sent().getLast();
        assertThat(userText(body)).contains("Number of covers: 1", "Green and calm", "My own cover");
        JsonNode schema = body.get("output_config").get("format").get("schema");
        assertThat(schema.get("properties").get("covers").get("items").get("properties").get("palette")
            .get("enum").toString()).contains("ocean", "sakura");
        assertThat(body.get("max_tokens").asInt()).isEqualTo(AiCoverJob.MAX_TOKENS);
        assertThat(documents(body)).isEmpty();
    }

    @Test
    void anSvgTheServerDoesNotDrawIsAskedForAgainAndThenFails() {
        String token = sponsorFull(SPONSOR_A);
        String quizId = quiz(token, "Bad svg " + UUID.randomUUID());
        for (int i = 0; i < 3; i++) {
            CLAUDE.script(MESSAGES, answer("claude-opus-5-5", "end_turn", illustration(
                "<svg viewBox=\"0 0 1200 630\"><script>alert(1)</script><text>Hi</text></svg>", "T", "top"), 100, 50));
        }
        long files = count("sys_file");
        Object aiJobId = requestCover(token, quizId).get("aiJobId");
        runUntilDone(aiJobId);
        assertThat(job(aiJobId)).containsEntry("status", QbAi.FAILED).containsEntry("failure_code",
            AiCodes.BAD_RESPONSE).containsEntry("cover_1", null);
        // Every attempt was answered and charged.
        assertThat(((Number) job(aiJobId).get("output_tokens")).longValue()).isEqualTo(150);
        assertThat(count("sys_file")).isEqualTo(files);
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

        // Catalogue designs outside the catalogue, or with texts too long: dropped; none left, the job failed, nothing
        // stored.
        String strayQuiz = quiz(token, "Stray " + UUID.randomUUID());
        CLAUDE.script(MESSAGES, answer("claude-opus-5-5", "end_turn", JSON.writeValueAsString(Map.of("covers",
            List.of(Map.of("palette", "neon", "layout", "center", "motif", "circles", "text", "Hi"),
                Map.of("palette", "ocean", "layout", "center", "motif", "circles", "text", "x".repeat(41))))),
            100, 50));
        Object stray = requestCover(token, strayQuiz, "style", "GRAPHIC").get("aiJobId");
        runJobs();
        assertThat(job(stray)).containsEntry("status", QbAi.FAILED).containsEntry("failure_code",
            AiCodes.NO_CANDIDATES);
        assertThat(((Number) job(stray).get("invalid_count")).intValue()).isEqualTo(2);
        assertThat(count("sys_file")).isEqualTo(files);

        // One good design of two: kept.
        String oneQuiz = quiz(token, "One " + UUID.randomUUID());
        CLAUDE.script(MESSAGES, answer("claude-opus-5-5", "end_turn", JSON.writeValueAsString(Map.of("covers",
            List.of(Map.of("palette", "mint", "layout", "left", "motif", "grid", "text", "Only one"),
                Map.of("palette", "ocean", "layout", "diagonal", "motif", "circles", "text", "Bad layout")))),
            100, 50));
        Object one = requestCover(token, oneQuiz, "style", "GRAPHIC").get("aiJobId");
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
