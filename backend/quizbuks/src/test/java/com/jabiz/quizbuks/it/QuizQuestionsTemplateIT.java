package com.jabiz.quizbuks.it;

import com.jabiz.param.ParamValues;
import com.jabiz.quizbuks.content.ContentLanguages;
import com.jabiz.quizbuks.content.ContentLimits;
import com.jabiz.quizbuks.content.QuizBankLimits;
import com.jabiz.quizbuks.setup.QbParams;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-S6 (docs/quizbuks/plans/impl/F-S6.md §3.1, §3.5): the editor reads a large bank a page at a time, searched by text
 * or number, and the bank's limits from the template that reads them as the processes do.
 */
class QuizQuestionsTemplateIT extends ContentItSupport {

    private Map<String, Object> page(String quizId, Map<String, Object> more, int offset, int limit) {
        Map<String, Object> params = new HashMap<>(more);
        params.put("quizId", quizId);
        return template("qb.sponsor.quiz-questions", sponsorA(), body("params", params, "offset", offset, "limit",
            limit));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("items");
    }

    private static int number(Object value) {
        return ((Number) value).intValue();
    }

    @Test
    void aBankIsReadAPageAtATimeAndSearchedByTextOrNumber() {
        String quizId = newQuiz(sponsorA(), "Paged");
        List<String> ids = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            ids.add((String) run("QB_QUESTION_SAVE", sponsorA(), body("quizId", quizId, "stem",
                (i % 4 == 0 ? "Capital " : "River ") + i, "points", i, "options",
                List.of(body("text", "a", "correct", true), body("text", "b"), body("text", "c")))).get("itemId"));
        }
        run("QB_QUIZ_LANGUAGES_SET", sponsorA(), body("quizId", quizId, "languages", List.of("ja", "zh")));
        run("QB_TRANSLATION_SAVE", sponsorA(), body("quizId", quizId, "language", "ja", "entries", List.of(
            body("key", ContentLanguages.stem(ids.get(0)), "text", "川 1"))));
        run("QB_TRANSLATION_SAVE", sponsorA(), body("quizId", quizId, "language", "zh", "entries", List.of(
            body("key", ContentLanguages.stem(ids.get(0)), "text", "河 1"))));

        Map<String, Object> second = page(quizId, Map.of(), 5, 5);
        assertThat(number(second.get("total"))).isEqualTo(12);
        assertThat(rows(second)).extracting(row -> number(row.get("questionNo"))).containsExactly(6, 7, 8, 9, 10);
        assertThat(rows(second).getFirst()).containsEntry("questionId", ids.get(5)).containsEntry("stem", "River 6");
        assertThat(number(rows(second).getFirst().get("optionCount"))).isEqualTo(3);
        Map<String, Object> first = rows(page(quizId, Map.of(), 0, 1)).getFirst();
        assertThat(number(first.get("translated"))).isEqualTo(2);

        // By text, ignoring case, and the questions found keep their numbers.
        Map<String, Object> found = page(quizId, Map.of("q", "capital"), 0, 50);
        assertThat(number(found.get("total"))).isEqualTo(3);
        assertThat(rows(found)).extracting(row -> number(row.get("questionNo"))).containsExactly(4, 8, 12);
        // A % is a %, not a wildcard.
        assertThat(rows(page(quizId, Map.of("q", "%"), 0, 50))).isEmpty();
        // By number.
        assertThat(rows(page(quizId, Map.of("no", 7), 0, 50))).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("questionId", ids.get(6)));

        // A move changes the numbers: they are the order's.
        run("QB_QUESTION_MOVE", sponsorA(), body("quizId", quizId, "questionId", ids.get(11), "beforeQuestionId",
            ids.get(0)));
        assertThat(rows(page(quizId, Map.of(), 0, 2))).extracting(row -> row.get("questionId"))
            .containsExactly(ids.get(11), ids.get(0));

        // Another sponsor sees nothing of it.
        assertThat(rows(template("qb.sponsor.quiz-questions", sponsorB(), body("params", Map.of("quizId", quizId)))))
            .isEmpty();
    }

    @Test
    void theSummaryAndTheBadgesAreCountedByTheServer() {
        // F-S6d (§3.5c): the editor reads no question of its own for the summary line or the rows' badges.
        String quizId = newQuiz(sponsorA(), "Summary");
        Map<String, Object> empty = items("qb.sponsor.quiz-question-summary", sponsorA(),
            body("params", Map.of("quizId", quizId))).getFirst();
        assertThat(number(empty.get("questionCount"))).isZero();
        assertThat(number(empty.get("fullScore"))).isZero();
        assertThat(empty.get("firstUnmarkedId")).isNull();

        String single = (String) run("QB_QUESTION_SAVE", sponsorA(), body("quizId", quizId, "stem", "One", "points", 2,
            "options", List.of(body("text", "a", "correct", true), body("text", "b")))).get("itemId");
        String multiple = (String) run("QB_QUESTION_SAVE", sponsorA(), body("quizId", quizId, "stem", "Two", "points",
            3, "options", List.of(body("text", "a", "correct", true), body("text", "b", "correct", true)))).get("itemId");
        String unmarked = (String) run("QB_QUESTION_SAVE", sponsorA(), body("quizId", quizId, "stem", "Three",
            "options", List.of(body("text", "a"), body("text", "b")))).get("itemId");
        run("QB_QUESTION_SAVE", sponsorA(), body("quizId", quizId, "stem", "Four", "options",
            List.of(body("text", "a"), body("text", "b"))));
        Map<String, Object> summary = items("qb.sponsor.quiz-question-summary", sponsorA(),
            body("params", Map.of("quizId", quizId))).getFirst();
        assertThat(number(summary.get("questionCount"))).isEqualTo(4);
        assertThat(number(summary.get("fullScore"))).isEqualTo(2 + 3 + 1 + 1);
        assertThat(number(summary.get("singleCount"))).isEqualTo(1);
        assertThat(number(summary.get("multipleCount"))).isEqualTo(1);
        assertThat(number(summary.get("unmarkedCount"))).isEqualTo(2);
        assertThat(summary).containsEntry("firstUnmarkedId", unmarked);
        assertThat(number(summary.get("firstUnmarkedNo"))).isEqualTo(3);
        // The first one unmarked follows the order.
        run("QB_QUESTION_MOVE", sponsorA(), body("quizId", quizId, "questionId", unmarked, "beforeQuestionId", null));
        Map<String, Object> moved = items("qb.sponsor.quiz-question-summary", sponsorA(),
            body("params", Map.of("quizId", quizId))).getFirst();
        assertThat(number(moved.get("firstUnmarkedNo"))).isEqualTo(3);
        assertThat(moved).doesNotContainEntry("firstUnmarkedId", unmarked);

        // The rows' badges: the correct options and whether an option has an image.
        List<Map<String, Object>> rows = rows(page(quizId, Map.of(), 0, 10));
        assertThat(rows).extracting(row -> row.get("questionId"), row -> number(row.get("correctCount")),
            row -> row.get("optionImages")).startsWith(org.assertj.core.groups.Tuple.tuple(single, 1, false),
            org.assertj.core.groups.Tuple.tuple(multiple, 2, false));

        // Another sponsor: no summary at all.
        assertThat(items("qb.sponsor.quiz-question-summary", sponsorB(), body("params", Map.of("quizId", quizId))))
            .isEmpty();
    }

    private Map<String, Object> limits() {
        return items("qb.sponsor.quiz-limits", sponsorA(), body()).getFirst();
    }

    /** The template's limits are the processes' (QuizBankLimits) for the same parameters. */
    private void sameAsTheProcesses(Map<String, Object> values) {
        ParamValues params = new ParamValues(Instant.EPOCH, values);
        assertThat(number(limits().get("maxQuestions"))).isEqualTo(QuizBankLimits.maxQuestions(params));
        assertThat(number(limits().get("maxPerAttempt"))).isEqualTo(QuizBankLimits.maxPerAttempt(params));
        assertThat(number(limits().get("importBatch"))).isEqualTo(QuizBankLimits.importBatch(params));
        assertThat(number(limits().get("importBatchBytes"))).isEqualTo(QuizBankLimits.importBatchBytes(params));
    }

    @Test
    void theLimitsAreReadAsTheProcessesReadThem() {
        try {
            sameAsTheProcesses(Map.of(QbParams.CONTENT_MAX_QUESTIONS, new java.math.BigDecimal(1000),
                QbParams.PLAY_MAX_PER_ATTEMPT, new java.math.BigDecimal(100), QbParams.CONTENT_IMPORT_BATCH,
                new java.math.BigDecimal(200)));
            setParam(QbParams.CONTENT_MAX_QUESTIONS, "5000");
            setParam(QbParams.PLAY_MAX_PER_ATTEMPT, "20");
            setParam(QbParams.CONTENT_IMPORT_BATCH, "0");
            sameAsTheProcesses(Map.of(QbParams.CONTENT_MAX_QUESTIONS, new java.math.BigDecimal(5000),
                QbParams.PLAY_MAX_PER_ATTEMPT, new java.math.BigDecimal(20), QbParams.CONTENT_IMPORT_BATCH,
                java.math.BigDecimal.ZERO));
            assertThat(limits()).containsEntry("maxPerAttempt", 20).containsEntry("importBatchBytes", 3_145_728);
        } finally {
            setParam(QbParams.CONTENT_MAX_QUESTIONS, "1000");
            setParam(QbParams.PLAY_MAX_PER_ATTEMPT, "100");
            setParam(QbParams.CONTENT_IMPORT_BATCH, "200");
        }
    }

    @Test
    void theBytesOfARequestAreCutToTheSmallestAndTheCeiling() {
        try {
            assertThat(limits()).containsEntry("importBatchBytes", 3_145_728);
            // F-F11a: the bytes of a request are cut to the smallest and the technical ceiling.
            setParam(QbParams.CONTENT_IMPORT_BATCH_BYTES, "100");
            sameAsTheProcesses(Map.of(QbParams.CONTENT_IMPORT_BATCH_BYTES, new java.math.BigDecimal(100)));
            assertThat(limits()).containsEntry("importBatchBytes", ContentLimits.IMPORT_BATCH_BYTES_MIN);
            setParam(QbParams.CONTENT_IMPORT_BATCH_BYTES, "9000000");
            sameAsTheProcesses(Map.of(QbParams.CONTENT_IMPORT_BATCH_BYTES, new java.math.BigDecimal(9_000_000)));
            assertThat(limits()).containsEntry("importBatchBytes", ContentLimits.IMPORT_BATCH_BYTES_TECH);
            setParam(QbParams.CONTENT_IMPORT_BATCH_BYTES, "1000000");
            sameAsTheProcesses(Map.of(QbParams.CONTENT_IMPORT_BATCH_BYTES, new java.math.BigDecimal(1_000_000)));
            assertThat(limits()).containsEntry("importBatchBytes", 1_000_000);
        } finally {
            setParam(QbParams.CONTENT_IMPORT_BATCH_BYTES, "3145728");
        }
    }

    @Test
    void theQuizListIsSearchedByTitleTopicNameAndCustomWord() {
        // F-S6 §3.2 (confirmed 2026-11-08): topics belong to the quiz; its list finds them by name in any language.
        if (query("SELECT 1 FROM qb_topic WHERE topic_id = ?", "volcano").isEmpty()) {
            run(com.jabiz.quizbuks.topic.TopicProcesses.ADD, com.jabiz.runtime.test.TestTokens.bearer(tokens,
                "topic-admin", com.jabiz.quizbuks.QbPermissions.ADMIN_TOPIC_READ,
                com.jabiz.quizbuks.QbPermissions.ADMIN_TOPIC_WRITE), body("topicId", "volcano", "nameZh", "火山学",
                "nameJa", "火山の科学", "nameEn", "Volcanology"));
        }
        String byWord = newQuiz(sponsorA(), "Mountains");
        run(com.jabiz.quizbuks.content.QuizTopics.SET, sponsorA(), body("quizId", byWord, "topics",
            List.of("volcano"), "customTopics", List.of("Lava flows")));
        String byTitle = newQuiz(sponsorA(), "Volcanoes of Japan");
        String other = newQuiz(sponsorA(), "Rivers");

        java.util.function.Function<String, List<Object>> search = q -> items("qb.sponsor.quizzes", sponsorA(),
            body("params", Map.of("q", q))).stream().map(row -> row.get("quizId")).toList();
        assertThat(search.apply("volcan")).contains(byWord, byTitle).doesNotContain(other);
        assertThat(search.apply("火山")).contains(byWord).doesNotContain(byTitle, other);
        assertThat(search.apply("lava")).containsExactly(byWord);
        // Not a piece of the JSON the custom words are kept in.
        assertThat(search.apply("\",\"")).isEmpty();
        assertThat(search.apply("river")).containsExactly(other);
    }
}
