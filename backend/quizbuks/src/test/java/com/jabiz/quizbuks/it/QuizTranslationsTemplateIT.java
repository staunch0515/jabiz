package com.jabiz.quizbuks.it;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jabiz.quizbuks.content.ContentLanguages;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-S6c (docs/quizbuks/plans/impl/F-S6.md §3.1, §3.5a): the translation page reads a large bank's questions in one
 * language a page at a time, each with its options, every text marked missing, out of date or done by the rule of
 * {@code QuizLanguages}; searched by text, or only what needs doing.
 */
class QuizTranslationsTemplateIT extends ContentItSupport {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Map<String, Object> page(String token, String quizId, String language, Map<String, Object> more,
        int offset, int limit) {
        Map<String, Object> params = new HashMap<>(more);
        params.put("quizId", quizId);
        params.put("language", language);
        return template("qb.sponsor.quiz-translations", token, body("params", params, "offset", offset, "limit",
            limit, "count", true));
    }

    private Map<String, Object> page(String quizId, String language, Map<String, Object> more, int offset,
        int limit) {
        return page(sponsorA(), quizId, language, more, offset, limit);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("items");
    }

    private static int number(Object value) {
        return ((Number) value).intValue();
    }

    private static List<Map<String, Object>> options(Map<String, Object> row) throws Exception {
        return JSON.readValue((String) row.get("options"), new TypeReference<>() {});
    }

    private Map<String, Object> only(String quizId, String language, int no) {
        return rows(page(quizId, language, Map.of(), no - 1, 1)).getFirst();
    }

    @Test
    void aBankIsTranslatedAPageAtATimeWithEveryTextsState() throws Exception {
        String quizId = newQuiz(sponsorA(), "Translated");
        List<String> ids = new ArrayList<>();
        List<List<String>> optionIds = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            Map<String, Object> saved = run("QB_QUESTION_SAVE", sponsorA(), body("quizId", quizId, "stem",
                (i % 4 == 0 ? "Capital " : "River ") + i, "options", List.of(body("text", "Yes " + i, "correct",
                    true), body("text", i == 5 ? "Lake Biwa" : "No " + i))));
            ids.add((String) saved.get("itemId"));
            @SuppressWarnings("unchecked")
            List<String> parts = (List<String>) saved.get("partIds");
            optionIds.add(parts);
        }
        run("QB_QUIZ_LANGUAGES_SET", sponsorA(), body("quizId", quizId, "languages", List.of("ja", "zh")));
        // Question 1 done in ja (text and both options), only its text in zh; question 2's text in ja.
        run("QB_TRANSLATION_SAVE", sponsorA(), body("quizId", quizId, "language", "ja", "entries", List.of(
            body("key", ContentLanguages.stem(ids.get(0)), "text", "川 1"),
            body("key", ContentLanguages.optionText(optionIds.get(0).get(0)), "text", "はい 1"),
            body("key", ContentLanguages.optionText(optionIds.get(0).get(1)), "text", "いいえ 1"),
            body("key", ContentLanguages.stem(ids.get(1)), "text", "川 2"))));
        run("QB_TRANSLATION_SAVE", sponsorA(), body("quizId", quizId, "language", "zh", "entries", List.of(
            body("key", ContentLanguages.stem(ids.get(0)), "text", "河 1"))));

        // A page: the numbers are the quiz's order, counted.
        Map<String, Object> second = page(quizId, "ja", Map.of(), 5, 5);
        assertThat(number(second.get("total"))).isEqualTo(12);
        assertThat(rows(second)).extracting(row -> number(row.get("questionNo"))).containsExactly(6, 7, 8, 9, 10);
        assertThat(rows(second).getFirst()).containsEntry("questionId", ids.get(5)).containsEntry("stem", "River 6")
            .containsEntry("stemState", "missing");
        assertThat(options(rows(second).getFirst())).extracting(o -> o.get("text"), o -> o.get("no"),
            o -> o.get("state")).containsExactly(org.assertj.core.groups.Tuple.tuple("Yes 6", 1, "missing"),
            org.assertj.core.groups.Tuple.tuple("No 6", 2, "missing"));

        // The language's own translations: question 1 is done in ja, its options too; in zh only its text.
        Map<String, Object> first = only(quizId, "ja", 1);
        assertThat(first).containsEntry("stemText", "川 1").containsEntry("stemState", "done")
            .containsEntry("stemBy", "SPONSOR");
        assertThat(number(first.get("pending"))).isZero();
        assertThat(options(first)).extracting(o -> o.get("translation"), o -> o.get("state"), o -> o.get("optionId"))
            .containsExactly(org.assertj.core.groups.Tuple.tuple("はい 1", "done", optionIds.get(0).get(0)),
                org.assertj.core.groups.Tuple.tuple("いいえ 1", "done", optionIds.get(0).get(1)));
        Map<String, Object> firstZh = only(quizId, "zh", 1);
        assertThat(firstZh).containsEntry("stemText", "河 1").containsEntry("stemState", "done");
        assertThat(number(firstZh.get("pending"))).isEqualTo(2);
        assertThat(options(firstZh)).extracting(o -> o.get("translation")).containsOnlyNulls();
        // A language the quiz does not have (here its base): nothing of another language leaks in.
        assertThat(only(quizId, "en", 1)).containsEntry("stemText", null).containsEntry("stemState", "missing");

        // Only what needs doing: in ja, every question but the first.
        Map<String, Object> pending = page(quizId, "ja", Map.of("untranslated", true), 0, 50);
        assertThat(number(pending.get("total"))).isEqualTo(11);
        assertThat(rows(pending)).extracting(row -> number(row.get("questionNo"))).startsWith(2, 3);

        // The base changes: the translations made from the old text are out of date, and need doing again.
        run("QB_QUESTION_SAVE", sponsorA(), body("quizId", quizId, "questionId", ids.get(0), "stem", "River one",
            "options", List.of(body("optionId", optionIds.get(0).get(0), "text", "Yes 1", "correct", true),
                body("optionId", optionIds.get(0).get(1), "text", "No, never"))));
        Map<String, Object> changed = only(quizId, "ja", 1);
        assertThat(changed).containsEntry("stemText", "川 1").containsEntry("stemState", "stale");
        assertThat(options(changed)).extracting(o -> o.get("state")).containsExactly("done", "stale");
        assertThat(number(changed.get("pending"))).isEqualTo(2);
        assertThat(number(page(quizId, "ja", Map.of("untranslated", true), 0, 50).get("total"))).isEqualTo(12);
        // Translated again from the new text: done (the hash is the processes' own, ContentLanguages.sourceHash).
        run("QB_TRANSLATION_SAVE", sponsorA(), body("quizId", quizId, "language", "ja", "entries", List.of(
            body("key", ContentLanguages.stem(ids.get(0)), "text", "川 一"),
            body("key", ContentLanguages.optionText(optionIds.get(0).get(1)), "text", "いいえ、決して"))));
        assertThat(only(quizId, "ja", 1)).containsEntry("stemState", "done");
        assertThat(number(only(quizId, "ja", 1).get("pending"))).isZero();

        // By text, in the question's or an option's, ignoring case; the questions found keep their numbers.
        Map<String, Object> found = page(quizId, "ja", Map.of("q", "capital"), 0, 50);
        assertThat(number(found.get("total"))).isEqualTo(3);
        assertThat(rows(found)).extracting(row -> number(row.get("questionNo"))).containsExactly(4, 8, 12);
        assertThat(rows(page(quizId, "ja", Map.of("q", "biwa"), 0, 50))).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("questionId", ids.get(4)));
        assertThat(rows(page(quizId, "ja", Map.of("q", "%"), 0, 50))).isEmpty();
        // Searched and filtered together.
        assertThat(number(page(quizId, "zh", Map.of("q", "river", "untranslated", true), 0, 50).get("total")))
            .isEqualTo(9);

        // Another sponsor sees nothing of it.
        assertThat(rows(page(sponsorB(), quizId, "ja", Map.of(), 0, 50))).isEmpty();
    }

    @Test
    void theHashIsOfTheNormalFormAsTheProcessesCompute() throws Exception {
        String quizId = newQuiz(sponsorA(), "Forms");
        // "é" written as e + combining accent: the hash is of the NFC form, as the processes compute it.
        String decomposed = "Café?";
        String questionId = question(sponsorA(), quizId, decomposed);
        run("QB_QUIZ_LANGUAGES_SET", sponsorA(), body("quizId", quizId, "languages", List.of("ja")));
        run("QB_TRANSLATION_SAVE", sponsorA(), body("quizId", quizId, "language", "ja", "entries", List.of(
            body("key", ContentLanguages.stem(questionId), "text", "Café?"))));
        Map<String, Object> row = only(quizId, "ja", 1);
        assertThat(row).containsEntry("stemState", "done");
        // Its options are not translated yet.
        assertThat(options(row)).extracting(o -> o.get("state")).containsExactly("missing", "missing");
        assertThat(number(row.get("pending"))).isEqualTo(2);
    }

    private Map<String, Map<String, Object>> progress(String token, String quizId) {
        Map<String, Map<String, Object>> byLanguage = new java.util.LinkedHashMap<>();
        items("qb.sponsor.quiz-translation-progress", token, body("params", Map.of("quizId", quizId)))
            .forEach(row -> byLanguage.put((String) row.get("language"), row));
        return byLanguage;
    }

    @Test
    void theProgressCountsEveryLanguagesMissingAndStaleTextsAsThePagesDo() {
        String quizId = newQuiz(sponsorA(), "Progress");
        List<String> ids = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            ids.add(question(sponsorA(), quizId, "Question " + i));
        }
        // Before any other language: nothing to report.
        assertThat(progress(sponsorA(), quizId)).isEmpty();
        run("QB_QUIZ_LANGUAGES_SET", sponsorA(), body("quizId", quizId, "languages", List.of("ja", "zh")));
        run("QB_TRANSLATION_SAVE", sponsorA(), body("quizId", quizId, "language", "ja", "entries", List.of(
            body("key", ContentLanguages.TITLE, "text", "進捗"),
            body("key", ContentLanguages.stem(ids.get(0)), "text", "問 1"),
            body("key", ContentLanguages.stem(ids.get(1)), "text", "問 2"))));
        // The title and 3 questions of 2 options each, no introduction: 10 texts.
        assertThat(progress(sponsorA(), quizId)).containsOnlyKeys("ja", "zh");
        assertThat(number(progress(sponsorA(), quizId).get("ja").get("missing"))).isEqualTo(7);
        assertThat(number(progress(sponsorA(), quizId).get("ja").get("stale"))).isZero();
        assertThat(number(progress(sponsorA(), quizId).get("zh").get("missing"))).isEqualTo(10);

        // The base changes: what was made from the old text is out of date.
        run("QB_QUIZ_SAVE", sponsorA(), body("quizId", quizId, "title", "Progress, renamed", "language", "en"));
        run("QB_QUESTION_SAVE", sponsorA(), body("quizId", quizId, "questionId", ids.get(0), "stem", "Question one"));
        Map<String, Object> ja = progress(sponsorA(), quizId).get("ja");
        assertThat(number(ja.get("missing"))).isEqualTo(7);
        assertThat(number(ja.get("stale"))).isEqualTo(2);
        // The questions' part is the sum of the translation page's pending counts.
        int pending = rows(page(quizId, "ja", Map.of(), 0, 50)).stream().mapToInt(row -> number(row.get("pending")))
            .sum();
        assertThat(pending).as("all but the stale title")
            .isEqualTo(number(ja.get("missing")) + number(ja.get("stale")) - 1);

        // Another sponsor sees nothing of it.
        assertThat(progress(sponsorB(), quizId)).isEmpty();
    }

    @Test
    void theQuizsOwnTranslationsAreReadByKey() {
        String quizId = newQuiz(sponsorA(), "By key");
        String questionId = question(sponsorA(), quizId, "Question");
        run("QB_QUIZ_LANGUAGES_SET", sponsorA(), body("quizId", quizId, "languages", List.of("ja")));
        run("QB_TRANSLATION_SAVE", sponsorA(), body("quizId", quizId, "language", "ja", "entries", List.of(
            body("key", ContentLanguages.TITLE, "text", "キー"),
            body("key", ContentLanguages.stem(questionId), "text", "問"))));
        Map<String, Object> page = client.post()
            .uri("/api/datasets/urn:jabiz:dataset:sponsor:QbContentTranslation/query")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .header(org.springframework.http.HttpHeaders.AUTHORIZATION, sponsorA())
            .bodyValue(body("filters", List.of(body("field", "quizId", "op", "eq", "value", quizId),
                body("field", "elementKey", "op", "in", "values", List.of(ContentLanguages.TITLE,
                    ContentLanguages.INTRO, ContentLanguages.COVER))), "limit", 100))
            .exchange().expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> found = (List<Map<String, Object>>) page.get("items");
        assertThat(found).singleElement().satisfies(item -> assertThat(item.toString()).contains("キー"));
    }
}
