package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.content.ContentCodes;
import com.jabiz.quizbuks.setup.QbParams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-S6 (docs/quizbuks/plans/impl/F-S6.md §3.4): a large bank imported into an existing quiz in batches, each its own
 * transaction within the batch and bank limits, and a batch undone once, keeping the questions changed since.
 */
class QuestionImportIT extends ContentItSupport {

    @AfterEach
    void defaults() {
        setParam(QbParams.CONTENT_IMPORT_BATCH, "200");
        setParam(QbParams.CONTENT_MAX_QUESTIONS, "1000");
    }

    /** A batch of {@code count} questions numbered from 1, its texts starting with {@code prefix}. */
    private static Map<String, Object> batch(String prefix, int count) {
        Map<String, Object> document = LargeBankIT.bank(count);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> questions = (List<Map<String, Object>>) document.get("questions");
        for (int i = 0; i < questions.size(); i++) {
            questions.get(i).put("stem", prefix + " " + (i + 1));
        }
        return document;
    }

    private Map<String, Object> importBatch(String quizId, Map<String, Object> document) {
        return run("QB_QUIZ_IMPORT_QUESTIONS", sponsorA(), body("quizId", quizId, "document", document));
    }

    private Map<String, Object> refusedBatch(String quizId, Map<String, Object> document) {
        return refused("QB_QUIZ_IMPORT_QUESTIONS", sponsorA(), body("quizId", quizId, "document", document), 422);
    }

    /** The quiz's question texts in their order. */
    private static List<String> stems(String quizId) {
        return query("SELECT stem FROM (SELECT DISTINCT ON (question_id) * FROM qb_question_version"
            + " ORDER BY question_id, effect_start_time DESC, version_no DESC) v WHERE quiz_id = ?::uuid"
            + " AND NOT is_deleted ORDER BY seq, question_id", quizId).stream().map(row -> (String) row.get("stem"))
            .toList();
    }

    private static String idOf(String quizId, String stem) {
        return String.valueOf(query("SELECT question_id FROM qb_question_version WHERE quiz_id = ?::uuid AND stem = ?",
            quizId, stem).getFirst().get("question_id"));
    }

    @Test
    void batchesAreImportedWithinTheLimitsAndOneIsUndoneKeepingWhatWasChanged() {
        String quizId = (String) run("QB_QUIZ_SAVE", sponsorA(), body("title", "Bank", "language", "en"))
            .get("quizId");
        question(sponsorA(), quizId, "Own");
        run("QB_QUIZ_LANGUAGES_SET", sponsorA(), body("quizId", quizId, "languages", List.of("ja")));
        setParam(QbParams.CONTENT_IMPORT_BATCH, "5");

        // Over the batch size: refused as a whole.
        assertThat(codes(refusedBatch(quizId, batch("Too", 6)))).containsExactly(ContentCodes.IMPORT_BATCH_TOO_LARGE);
        Map<String, Object> first = importBatch(quizId, batch("A", 5));
        assertThat(first).containsEntry("questions", 5).containsEntry("skippedLanguages", List.of("zh"));
        importBatch(quizId, batch("B", 3));
        assertThat(stems(quizId)).containsExactly("Own", "A 1", "A 2", "A 3", "A 4", "A 5", "B 1", "B 2", "B 3");
        assertThat(latest("qb_quiz_version", "quiz_id", "quiz_id = '" + quizId + "'").getFirst()
            .get("question_count").toString()).isEqualTo("9");
        // Their Japanese texts came with them; the Chinese, a language the quiz has not, did not.
        assertThat(latest("qb_content_translation_version", "translation_id", "quiz_id = '" + quizId
            + "' AND NOT is_deleted").stream().map(row -> row.get("language")).distinct().toList())
            .containsExactly("ja");

        // A batch that would pass the bank's limit: refused, the earlier batches stay.
        setParam(QbParams.CONTENT_MAX_QUESTIONS, "10");
        assertThat(codes(refusedBatch(quizId, batch("C", 2)))).containsExactly(ContentCodes.TOO_MANY_QUESTIONS);
        assertThat(stems(quizId)).hasSize(9);

        // One of the first batch changed, another only moved: undoing removes all but the changed one.
        String changed = idOf(quizId, "A 2");
        run("QB_QUESTION_SAVE", sponsorA(), body("quizId", quizId, "questionId", changed, "stem", "A 2",
            "points", 3));
        run("QB_QUESTION_MOVE", sponsorA(), body("quizId", quizId, "questionId", idOf(quizId, "A 4")));
        Map<String, Object> undone = run("QB_QUESTION_IMPORT_UNDO", sponsorA(), body("quizId", quizId, "importId",
            first.get("importId")));
        assertThat(undone).containsEntry("removed", 4).containsEntry("kept", List.of(changed));
        assertThat(stems(quizId)).containsExactly("Own", "A 2", "B 1", "B 2", "B 3");
        assertThat(query("SELECT removed_count, undone_at FROM qb_question_import WHERE import_id = ?",
            first.get("importId")).getFirst()).satisfies(row -> {
                assertThat(row.get("removed_count").toString()).isEqualTo("4");
                assertThat(row.get("undone_at")).isNotNull();
            });
        // The removed questions' translations went with them: those left are of the questions left.
        assertThat(latest("qb_content_translation_version", "translation_id", "quiz_id = '" + quizId
            + "' AND NOT is_deleted AND element_key LIKE 'question:%'")).extracting(row -> row.get("element_key"))
            .containsExactlyInAnyOrder(java.util.stream.Stream.of("A 2", "B 1", "B 2", "B 3")
                .map(stem -> "question:" + idOf(quizId, stem) + ".stem").toArray());
        // Once only.
        assertThat(codes(refused("QB_QUESTION_IMPORT_UNDO", sponsorA(), body("quizId", quizId, "importId",
            first.get("importId")), 422))).containsExactly(ContentCodes.IMPORT_UNDONE);
        // Not another sponsor's.
        refused("QB_QUESTION_IMPORT_UNDO", sponsorB(), body("quizId", quizId, "importId", first.get("importId")), 404);
    }

    @Test
    void onlyQuestionsOfTheSameKindAndLanguageAreImported() {
        String quizId = (String) run("QB_QUIZ_SAVE", sponsorA(), body("title", "Kinds", "language", "en"))
            .get("quizId");
        Map<String, Object> withMaterial = batch("M", 1);
        withMaterial.put("materials", List.of(new LinkedHashMap<>(Map.of("kind", "ARTICLE", "title", "Read",
            "body", "Text"))));
        assertThat(codes(refusedBatch(quizId, withMaterial))).contains(ContentCodes.IMPORT_QUESTIONS_ONLY);

        Map<String, Object> japanese = batch("J", 1);
        @SuppressWarnings("unchecked")
        Map<String, Object> heading = new LinkedHashMap<>((Map<String, Object>) japanese.get("quiz"));
        heading.put("language", "ja");
        japanese.put("quiz", heading);
        japanese.put("translations", Map.of());
        assertThat(codes(refusedBatch(quizId, japanese))).containsExactly(ContentCodes.IMPORT_LANGUAGE);

        String survey = (String) run("QB_QUIZ_SAVE", sponsorA(), body("title", "Asked", "kind", "SURVEY",
            "language", "en")).get("quizId");
        assertThat(codes(refusedBatch(survey, batch("S", 1)))).containsExactly(ContentCodes.QUIZ_KIND_FIXED);
        assertThat(stems(survey)).isEmpty();
        List<String> none = new ArrayList<>(stems(quizId));
        assertThat(none).isEmpty();
    }
}
