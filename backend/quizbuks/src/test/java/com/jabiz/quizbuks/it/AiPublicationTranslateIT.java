package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.content.ContentLanguages;
import com.jabiz.quizbuks.content.ai.AiCodes;
import com.jabiz.quizbuks.content.ai.AiProcesses;
import com.jabiz.quizbuks.content.ai.QbAi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Q73d-2 against the stub (docs/quizbuks/plans/Q73-content-languages.md §5): a publication's own texts translated as
 * candidates the sponsor uses in the publication's form, the job then closed as used; and a reviewer's comparison of
 * a publication's translations with their base texts, for reference, charged to the reviewer and never the sponsor,
 * read by the publication's reviewers only.
 */
class AiPublicationTranslateIT extends AiItSupport {

    @BeforeEach
    void freshDay() {
        aiOn();
        advance(Duration.ofDays(1));
    }

    /** A quiz in English with a complete Chinese translation, and its version: {quizId, versionId}. */
    @SuppressWarnings("unchecked")
    private List<String> bilingualVersion(String token, String optionText) {
        String quizId = (String) run("QB_QUIZ_SAVE", token, body("title", "Capitals", "language", "en",
            "intro", "Two capitals.", "timeLimitSec", 300)).get("quizId");
        Map<String, Object> q1 = run("QB_QUESTION_SAVE", token, body("quizId", quizId, "stem", "Capital of France?",
            "points", 5, "options", List.of(body("text", "Paris", "correct", true), body("text", "Lyon"))));
        List<String> options = (List<String>) q1.get("partIds");
        run("QB_QUIZ_LANGUAGES_SET", token, body("quizId", quizId, "languages", List.of("zh")));
        run("QB_TRANSLATION_SAVE", token, body("quizId", quizId, "language", "zh", "entries", List.of(
            body("key", ContentLanguages.TITLE, "text", "首都"),
            body("key", ContentLanguages.INTRO, "text", "两个首都。"),
            body("key", ContentLanguages.stem(q1.get("itemId")), "text", "法国的首都？"),
            body("key", ContentLanguages.optionText(options.get(0)), "text", optionText),
            body("key", ContentLanguages.optionText(options.get(1)), "text", "里昂"))));
        return List.of(quizId, (String) run("QB_QUIZ_PUBLISH_VERSION", token, body("quizId", quizId))
            .get("versionId"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> stored(Object aiJobId, String list) {
        Object candidates = job(aiJobId).get("candidates");
        return candidates == null ? List.of()
            : (List<Map<String, Object>>) JSON.readValue(candidates.toString(), Map.class).get(list);
    }

    @Test
    void aPublicationsTextsAreTranslatedAsCandidatesAndTheJobClosesOnceUsed() {
        String sponsor = newSponsor(unique("s-q73d2"));
        String token = sponsorFull(sponsor);
        List<String> quiz = bilingualVersion(token, "巴黎");
        Instant now = clock.instant();
        Map<String, Object> save = complete(quiz.get(1), "World capitals", now, now.plus(Duration.ofDays(7)));
        save.put("languages", List.of("en", "zh"));
        String publicationId = (String) run("QB_PUBLICATION_SAVE", token, save).get("publicationId");

        Object aiJobId = run(AiProcesses.PUBLICATION_TRANSLATE_REQUEST, token, body("quizId", quiz.get(0),
            "publicationId", publicationId, "language", "zh", "aiNoticeVersionNo", noticeVersion())).get("aiJobId");
        assertThat(job(aiJobId)).containsEntry("kind", QbAi.KIND_PUBLICATION_TEXTS);
        // A quiz's translation may run meanwhile: another reference, another kind.
        run(AiProcesses.TRANSLATE_REQUEST, token, body("quizId", quiz.get(0), "language", "zh", "all", true));
        runJobs();
        assertThat(job(aiJobId)).containsEntry("status", QbAi.READY);
        assertThat(stored(aiJobId, "translations")).extracting(c -> c.get("key"), c -> c.get("text"))
            .containsExactly(org.assertj.core.groups.Tuple.tuple("publication.name", "译:World capitals"));
        // A language the publication does not offer.
        assertThat(codes(refused(AiProcesses.PUBLICATION_TRANSLATE_REQUEST, token, body("quizId", quiz.get(0),
            "publicationId", publicationId, "language", "ja"), 422))).contains("QB_TRANSLATION_LANGUAGE");

        // The sponsor uses it in the form, saves the publication, and the job closes as used.
        save.put("publicationId", publicationId);
        save.put("translations", Map.of("zh", Map.of("name", "世界的首都")));
        run("QB_PUBLICATION_SAVE", token, save);
        // Not the sponsor's job: not found.
        String other = sponsorFull(newSponsor(unique("s-q73d2-other")));
        refused(AiProcesses.PUBLICATION_TEXTS_ACCEPT, other, body("aiJobId", aiJobId, "publicationId", publicationId,
            "used", 1), 404);
        run(AiProcesses.PUBLICATION_TEXTS_ACCEPT, token, body("aiJobId", aiJobId, "publicationId", publicationId,
            "used", 1));
        assertThat(job(aiJobId)).containsEntry("status", QbAi.ACCEPTED);
        assertThat(job(aiJobId).get("candidates")).isNull();
        // Now translated and up to date: nothing left to ask for.
        assertThat(codes(refused(AiProcesses.PUBLICATION_TRANSLATE_REQUEST, token, body("quizId", quiz.get(0),
            "publicationId", publicationId, "language", "zh", "aiNoticeVersionNo", noticeVersion()), 422)))
            .contains(AiCodes.NOTHING_TO_TRANSLATE);
    }

    @Test
    void aReviewerComparesTheTranslationsChargedToThePlatformNotTheSponsor() {
        String sponsor = newSponsor(unique("s-q73d2-check"));
        credit(sponsor, 100_000);
        String token = sponsorFull(sponsor);
        // The option's translation changes its meaning.
        List<String> quiz = bilingualVersion(token, "伦敦 [wrong]");
        Instant now = clock.instant();
        Map<String, Object> save = complete(quiz.get(1), "World capitals", now.plus(Duration.ofDays(1)),
            now.plus(Duration.ofDays(8)));
        save.put("languages", List.of("en", "zh"));
        save.put("translations", Map.of("zh", Map.of("name", "世界的首都")));
        String publicationId = (String) run("QB_PUBLICATION_SAVE", token, save).get("publicationId");
        run("QB_PUBLICATION_SUBMIT", token, body("publicationId", publicationId));

        // The sponsor may not ask for it; the reviewer may, of a language the publication offers.
        refused(AiProcesses.TRANSLATION_CHECK_REQUEST, token, body("publicationId", publicationId,
            "language", "zh"), 403);
        assertThat(codes(refused(AiProcesses.TRANSLATION_CHECK_REQUEST, reviewer(), body("publicationId",
            publicationId, "language", "ja"), 422))).contains("QB_TRANSLATION_LANGUAGE");
        Object aiJobId = run(AiProcesses.TRANSLATION_CHECK_REQUEST, reviewer(), body("publicationId", publicationId,
            "language", "zh")).get("aiJobId");
        runJobs();

        Map<String, Object> job = job(aiJobId);
        assertThat(job).containsEntry("kind", QbAi.KIND_TRANSLATION_CHECK).containsEntry("status", QbAi.READY)
            .containsEntry("sponsor_id", "reviewer");
        // The pairs were texts only.
        String user = userText(sent().getLast());
        assertThat(user).contains("Capital of France?", "法国的首都？", "World capitals", "世界的首都")
            .doesNotContain("correct", "points");
        List<Map<String, Object>> verdicts = stored(aiJobId, "verdicts");
        assertThat(verdicts).filteredOn(v -> Boolean.FALSE.equals(v.get("consistent")))
            .extracting(v -> v.get("key")).containsExactly("q1.o1.text");
        assertThat(verdicts).extracting(v -> v.get("key")).contains("title", "q1.stem", "publication.name");
        // The sponsor's usage is untouched; the reviewer's counts it.
        assertThat(usage(sponsor)).isEmpty();
        assertThat(((Number) usage("reviewer").get("jobs")).intValue()).isPositive();

        // The reviewers read it; the sponsor does not.
        List<Map<String, Object>> rows = items("qb.admin.ai-translation-check", reviewer(), Map.of("params",
            Map.of("publicationId", publicationId, "language", "zh"), "limit", 1));
        assertThat(rows).singleElement().satisfies(row -> assertThat(row).containsEntry("status", QbAi.READY));
    }
}
