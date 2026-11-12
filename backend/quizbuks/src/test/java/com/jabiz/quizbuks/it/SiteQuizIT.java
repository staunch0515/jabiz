package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.content.ai.AiCodes;
import com.jabiz.quizbuks.content.ai.AiProcesses;
import com.jabiz.quizbuks.content.ai.QbAi;
import com.jabiz.quizbuks.content.ai.SiteProfileProcesses;
import com.jabiz.quizbuks.content.ai.SiteQuizProcesses;
import com.jabiz.quizbuks.setup.QbParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A "get to know us" quiz from a website draft (F-C7b-2, docs/quizbuks/plans/impl/F-C7.md): the quiz is a draft made by
 * the usual import from the draft's articles, the drafted questions are kept as a ready job of that quiz and reach it
 * only through {@code QB_AI_ACCEPT}, each reviewed; one quiz a draft; nobody else's draft; nothing but a ready one.
 */
class SiteQuizIT extends AiItSupport {

    private static final String DRAFT = """
        {"failure": null,
         "package": {"basics": {"company": "Aini Inc.", "intro": {"ja": "ヘルメットの会社です。", "en": "A helmet company."}},
           "page": {"handle": "%s", "headline": {"ja": "毎日を安心して", "en": "Ride with confidence"},
             "website": "https://www.aini.example/"},
           "products": []},
         "sources": {}, "notFound": [], "translated": [],
         "materials": [
           {"title": "About us", "body": "We have made helmets since 1990.", "url": "https://www.aini.example/about"},
           {"title": "Safety", "body": "Every helmet is tested twice.", "url": "https://www.aini.example/safety"}],
         "questions": [
           {"stem": "Since when?", "options": [{"text": "1990", "correct": true}, {"text": "2000", "correct": false},
             {"text": "2010", "correct": false}, {"text": "2020", "correct": false}], "materials": [1]},
           {"stem": "How often is a helmet tested?", "options": [{"text": "Twice", "correct": true},
             {"text": "Once", "correct": false}, {"text": "Never", "correct": false}, {"text": "Weekly", "correct": false}],
             "materials": [2]},
           {"stem": "Rests on an article that is not there", "options": [{"text": "a", "correct": true},
             {"text": "b", "correct": false}, {"text": "c", "correct": false}, {"text": "d", "correct": false}],
             "materials": [9]}]}
        """;

    private String draft = DRAFT;

    @BeforeEach
    void switchedOn() {
        aiOn();
        if (query("SELECT 1 FROM sys_param_version WHERE param_key = ? AND param_value = 'true'",
            QbParams.SITE_PROFILE_ENABLED).isEmpty()) {
            param(QbParams.SITE_PROFILE_ENABLED, "true");
        }
        advance(Duration.ofDays(1));
        draft = DRAFT;
        answering = request -> {
            JsonNode body = JSON.readTree(request.body());
            return body.toString().contains("You read ONE company") && !request.path().endsWith("count_tokens")
                ? answer(body.get("model").asString(), "end_turn", draft.formatted("q-" + UUID.randomUUID().toString()
                    .substring(0, 8)), 9000, 5000) : null;
        };
    }

    private Object readyDraft(String token) {
        Object aiJobId = run(SiteProfileProcesses.REQUEST, token, body("siteAddress", "https://www.aini.example/",
            "language", "ja", "otherLanguages", List.of(), "rightsDeclared", true,
            "aiNoticeVersionNo", noticeVersion())).get("aiJobId");
        runJobs();
        assertThat(job(aiJobId)).containsEntry("status", QbAi.READY);
        return aiJobId;
    }

    @Test
    void theQuizIsADraftOfTheArticlesAndTheQuestionsWaitForReview() {
        String sponsor = newSponsor("site-quiz-" + UUID.randomUUID());
        String token = sponsorFull(sponsor);
        Object aiJobId = readyDraft(token);

        Map<String, Object> made = run(SiteQuizProcesses.QUIZ, token, body("aiJobId", aiJobId));
        String quizId = String.valueOf(made.get("quizId"));
        assertThat(((Number) made.get("materials")).intValue()).isEqualTo(2);
        // The third question names an article that is not there: the content rules clean that reference, not the question.
        assertThat(((Number) made.get("questions")).intValue()).isEqualTo(3);

        // The quiz: a draft of the sponsor's, with the articles in order and no question yet.
        Map<String, Object> quiz = latest("qb_quiz_version", "quiz_id", "quiz_id = '" + quizId + "'").getFirst();
        assertThat(quiz).containsEntry("title", "Aini Inc.を知ろう").containsEntry("status", "DRAFT");
        assertThat(((Number) quiz.get("question_count")).intValue()).isZero();
        List<Map<String, Object>> materials = latest("qb_material_version", "material_id",
            "quiz_id = '" + quizId + "' AND NOT is_deleted");
        assertThat(materials).extracting(row -> row.get("title")).containsExactlyInAnyOrder("About us", "Safety");
        assertThat(materials).extracting(row -> row.get("kind")).containsOnly("ARTICLE");

        // The questions: a ready job of that quiz, no usage counted twice, nothing added to the quiz.
        Object questionsJob = made.get("questionsJob");
        assertThat(job(questionsJob)).containsEntry("status", QbAi.READY).containsEntry("kind", QbAi.KIND_QUESTIONS)
            .containsEntry("quiz_id", UUID.fromString(quizId)).containsEntry("sponsor_id", sponsor);
        assertThat(((Number) job(questionsJob).get("input_tokens")).longValue()).isZero();
        assertThat(String.valueOf(job(questionsJob).get("candidates"))).contains("Since when?")
            .doesNotContain("\"materials\":[9]").contains("\"materials\":[2]");
        // The website draft stays for the profile's review, and is marked used.
        assertThat(job(aiJobId)).containsEntry("status", QbAi.READY);
        assertThat(job(aiJobId).get("site_quiz_id")).isEqualTo(UUID.fromString(quizId));
        assertThat(job(aiJobId).get("accepted_count")).isNull();
        assertThat(String.valueOf(job(aiJobId).get("candidates"))).contains("Aini Inc.");

        // One quiz a draft.
        assertThat(codes(refused(SiteQuizProcesses.QUIZ, token, body("aiJobId", aiJobId), 422)))
            .contains(AiCodes.SITE_QUIZ_MADE);

        // Questions reach the quiz only reviewed, through the usual acceptance.
        long revision = ((Number) quiz.get("revision")).longValue();
        Map<String, Object> unreviewed = body("candidateNo", 1, "stem", "Since when?", "points", 5, "options",
            List.of(body("text", "1990", "correct", true), body("text", "2000", "correct", false)));
        refused(AiProcesses.ACCEPT, token, body("aiJobId", questionsJob, "quizId", quizId, "baseRevision", revision,
            "questions", List.of(unreviewed)), 400);
        Map<String, Object> reviewed = new java.util.LinkedHashMap<>(unreviewed);
        reviewed.put("reviewed", true);
        assertThat(run(AiProcesses.ACCEPT, token, body("aiJobId", questionsJob, "quizId", quizId,
            "baseRevision", revision, "questions", List.of(reviewed))).get("questionIds")).asList().hasSize(1);
        assertThat(job(questionsJob)).containsEntry("status", QbAi.ACCEPTED);
    }

    @Test
    void anotherSponsorsDraftANotReadyOneOneWithoutArticlesAndAnExpiredOneMakeNothing() {
        String owner = newSponsor("site-quiz-a-" + UUID.randomUUID());
        String token = sponsorFull(owner);
        Object aiJobId = readyDraft(token);
        Object quizzesBefore = query("SELECT count(DISTINCT quiz_id) AS n FROM qb_quiz_version").getFirst().get("n");
        // Anyone else's draft is not found.
        refused(SiteQuizProcesses.QUIZ, sponsorFull(newSponsor("site-quiz-b-" + UUID.randomUUID())),
            body("aiJobId", aiJobId), 404);

        // One still being made.
        String other = sponsorFull(newSponsor("site-quiz-c-" + UUID.randomUUID()));
        Object queued = run(SiteProfileProcesses.REQUEST, other, body("siteAddress", "https://www.aini.example/",
            "language", "ja", "otherLanguages", List.of(), "rightsDeclared", true,
            "aiNoticeVersionNo", noticeVersion())).get("aiJobId");
        assertThat(codes(refused(SiteQuizProcesses.QUIZ, other, body("aiJobId", queued), 422)))
            .contains(AiCodes.JOB_NOT_READY);
        runJobs();

        // One without articles.
        draft = DRAFT.replaceAll("(?s)\"materials\": \\[.*?\\],\\s*\"questions\"", "\"materials\": [], \"questions\"");
        String third = sponsorFull(newSponsor("site-quiz-d-" + UUID.randomUUID()));
        Object bare = readyDraft(third);
        assertThat(codes(refused(SiteQuizProcesses.QUIZ, third, body("aiJobId", bare), 422)))
            .contains(AiCodes.SITE_NO_QUIZ);

        // One that has expired.
        advance(Duration.ofDays(30));
        assertThat(codes(refused(SiteQuizProcesses.QUIZ, sponsorFull(owner), body("aiJobId", aiJobId), 422)))
            .contains(AiCodes.JOB_EXPIRED);
        assertThat(query("SELECT count(DISTINCT quiz_id) AS n FROM qb_quiz_version").getFirst().get("n"))
            .isEqualTo(quizzesBefore);
    }
}
