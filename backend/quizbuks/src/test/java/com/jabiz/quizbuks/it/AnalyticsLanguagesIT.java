package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.content.ContentLanguages;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A publication's figures by the language its attempts are answered in (Q73e-1): up to the analytics job's last run
 * only, every offered language listed, the passes and rates only over the minimum group of completions in a language,
 * read by its sponsor and by the administrators, nobody else.
 */
class AnalyticsLanguagesIT extends AnalyticsItSupport {

    static final String LANGUAGES = "qb.sponsor.analytics.languages";
    static final String ADMIN_LANGUAGES = "qb.admin.publication-languages";

    /** A quiz in English with a complete Chinese translation (2 + 3 points); returns its version's key. */
    @SuppressWarnings("unchecked")
    private String bilingualVersion(String token) {
        String quizId = (String) run("QB_QUIZ_SAVE", token, body("title", "Capitals", "language", "en",
            "intro", "Two capitals.", "timeLimitSec", 300)).get("quizId");
        Map<String, Object> q1 = run("QB_QUESTION_SAVE", token, body("quizId", quizId, "stem", "Capital of France?",
            "points", 2, "options", List.of(body("text", "Paris", "correct", true), body("text", "Lyon"))));
        Map<String, Object> q2 = run("QB_QUESTION_SAVE", token, body("quizId", quizId, "stem", "Capital of Japan?",
            "points", 3, "options", List.of(body("text", "Tokyo", "correct", true), body("text", "Osaka"))));
        List<String> o1 = (List<String>) q1.get("partIds");
        List<String> o2 = (List<String>) q2.get("partIds");
        run("QB_QUIZ_LANGUAGES_SET", token, body("quizId", quizId, "languages", List.of("zh")));
        run("QB_TRANSLATION_SAVE", token, body("quizId", quizId, "language", "zh", "entries", List.of(
            body("key", ContentLanguages.TITLE, "text", "首都"),
            body("key", ContentLanguages.INTRO, "text", "两个首都。"),
            body("key", ContentLanguages.stem(q1.get("itemId")), "text", "法国的首都？"),
            body("key", ContentLanguages.optionText(o1.get(0)), "text", "巴黎"),
            body("key", ContentLanguages.optionText(o1.get(1)), "text", "里昂"),
            body("key", ContentLanguages.stem(q2.get("itemId")), "text", "日本的首都？"),
            body("key", ContentLanguages.optionText(o2.get(0)), "text", "东京"),
            body("key", ContentLanguages.optionText(o2.get(1)), "text", "大阪"))));
        return (String) run("QB_QUIZ_PUBLISH_VERSION", token, body("quizId", quizId)).get("versionId");
    }

    private List<Map<String, Object>> byLanguage(String template, String token, String publicationId) {
        return items(template, token, Map.of("params", Map.of("publicationId", publicationId), "limit", 10));
    }

    private void answer(String publicationId, String language, boolean right) {
        String token = taker(newTaker(unique("t-q73e"), "JP"));
        String attemptId = (String) run("QB_ATTEMPT_START", token, body("publicationId", publicationId,
            "language", language)).get("attemptId");
        clock.advance(Duration.ofSeconds(5));
        submit(token, attemptId, answers(right, right));
    }

    @Test
    void theFiguresOfEachLanguageOnlyOverTheMinimumGroup() {
        minGroup("3");
        String sponsor = newSponsor(unique("s-q73e"));
        credit(sponsor, 100_000);
        String token = sponsorToken(sponsor);
        Instant now = clock.instant();
        Map<String, Object> save = complete(bilingualVersion(token), "Capital cities", now, now.plus(Duration.ofDays(7)));
        save.put("regions", List.of("GLOBAL"));
        save.put("maxWinners", 1000);
        save.put("languages", List.of("zh", "en"));
        save.put("translations", Map.of("zh", Map.of("name", "世界的首都")));
        String publicationId = (String) run("QB_PUBLICATION_SAVE", token, save).get("publicationId");
        run("QB_PUBLICATION_SUBMIT", token, body("publicationId", publicationId));
        runAnalytics(clock.instant().plus(Duration.ofMinutes(1)));

        // Three in Chinese (all right), one in English (wrong).
        answer(publicationId, "zh", true);
        answer(publicationId, "zh", true);
        answer(publicationId, "zh", true);
        answer(publicationId, "en", false);

        // Not before the job has run: the figures do not follow each attempt.
        assertThat(byLanguage(LANGUAGES, token, publicationId)).extracting(r -> r.get("language"),
                r -> ((Number) r.get("starts")).intValue())
            .containsExactly(org.assertj.core.groups.Tuple.tuple("zh", 0), org.assertj.core.groups.Tuple.tuple("en", 0));

        runAnalytics(clock.instant().plus(Duration.ofMinutes(10)));
        List<Map<String, Object>> rows = byLanguage(LANGUAGES, token, publicationId);
        assertThat(rows).extracting(r -> r.get("language")).containsExactly("zh", "en");
        Map<String, Object> zh = rows.get(0);
        assertThat(((Number) zh.get("starts")).intValue()).isEqualTo(3);
        assertThat(((Number) zh.get("completions")).intValue()).isEqualTo(3);
        assertThat(((Number) zh.get("passes")).intValue()).isEqualTo(3);
        assertThat(((Number) zh.get("passRate")).doubleValue()).isEqualTo(1.0);
        assertThat(((Number) zh.get("averageScore")).doubleValue()).isEqualTo(5.0);
        assertThat(zh.get("suppressed")).isEqualTo(false);
        // One English answer: counted, its result not shown.
        Map<String, Object> en = rows.get(1);
        assertThat(((Number) en.get("completions")).intValue()).isEqualTo(1);
        assertThat(en.get("passes")).isNull();
        assertThat(en.get("passRate")).isNull();
        assertThat(en.get("averageScore")).isNull();
        assertThat(en.get("suppressed")).isEqualTo(true);

        // The administrators see the same; another sponsor nothing.
        assertThat(byLanguage(ADMIN_LANGUAGES, reviewer(), publicationId)).extracting(r -> r.get("language"),
                r -> ((Number) r.get("completions")).intValue())
            .containsExactly(org.assertj.core.groups.Tuple.tuple("zh", 3), org.assertj.core.groups.Tuple.tuple("en", 1));
        assertThat(byLanguage(LANGUAGES, sponsorToken(newSponsor(unique("s-q73e-other"))), publicationId)).isEmpty();
    }
}
