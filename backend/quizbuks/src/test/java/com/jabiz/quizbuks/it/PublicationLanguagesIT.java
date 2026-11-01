package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.content.ContentLanguages;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A publication in several languages (Q73b, docs/quizbuks/plans/Q73-content-languages.md §3): it offers some of its
 * version's languages, its own texts translated into them; one card, one budget; the feed lists it once in the
 * taker's first language it offers; an attempt answers in the language it started in, scored the same.
 */
class PublicationLanguagesIT extends PlayItSupport {

    /** A quiz in English with a complete Chinese translation; returns its version's key. */
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
        Map<String, Object> version = run("QB_QUIZ_PUBLISH_VERSION", token, body("quizId", quizId));
        assertThat(version.get("languages")).isEqualTo(List.of("en", "zh"));
        return (String) version.get("versionId");
    }

    @SuppressWarnings("unchecked")
    private static List<String> problemCodes(Map<String, Object> saved) {
        return ((List<Map<String, Object>>) saved.get("problems")).stream().map(p -> String.valueOf(p.get("code")))
            .toList();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aPublicationInTwoLanguagesIsOneCardShownInEachAndAnsweredInTheOneStarted() {
        String sponsor = newSponsor(unique("s-q73b"));
        credit(sponsor, 100_000);
        String token = sponsorToken(sponsor);
        String versionId = bilingualVersion(token);
        Instant now = clock.instant();
        Map<String, Object> save = complete(versionId, "Capital cities", now, now.plus(Duration.ofDays(7)));
        save.put("regions", List.of("GLOBAL"));
        save.put("maxWinners", 1000);

        // Offering Chinese needs its texts; a language the version lacks is not offered.
        save.put("languages", List.of("zh", "en", "ja"));
        Map<String, Object> first = run("QB_PUBLICATION_SAVE", token, save);
        assertThat(problemCodes(first)).contains("QB_PUBLICATION_TEXT_MISSING",
            "QB_PUBLICATION_LANGUAGE_NOT_IN_VERSION");
        String publicationId = (String) first.get("publicationId");
        save.put("publicationId", publicationId);
        save.put("languages", List.of("zh", "en"));
        save.put("translations", Map.of("zh", Map.of("name", "世界的首都")));
        assertThat(problemCodes(run("QB_PUBLICATION_SAVE", token, save))).doesNotContain(
            "QB_PUBLICATION_TEXT_MISSING", "QB_PUBLICATION_LANGUAGE_NOT_IN_VERSION");
        // The base name changed, the Chinese one not: it translates an older name until translated again.
        save.put("name", "Capitals of the world");
        assertThat(problemCodes(run("QB_PUBLICATION_SAVE", token, save))).contains("QB_PUBLICATION_TEXT_STALE");
        // Q73c: the sponsor may confirm it is still right for the new name instead of translating it again.
        save.put("confirmed", List.of("zh.name"));
        assertThat(problemCodes(run("QB_PUBLICATION_SAVE", token, save))).doesNotContain(
            "QB_PUBLICATION_TEXT_STALE");
        save.remove("confirmed");
        save.put("translations", Map.of("zh", Map.of("name", "世界各国的首都")));
        assertThat(problemCodes(run("QB_PUBLICATION_SAVE", token, save))).doesNotContain(
            "QB_PUBLICATION_TEXT_STALE");
        assertThat(run("QB_PUBLICATION_SUBMIT", token, body("publicationId", publicationId)).get("status"))
            .isIn("LIVE", "SCHEDULED");

        // One card, Chinese its main language, its texts in Chinese with the version's Chinese title.
        Map<String, Object> card = card(publicationId);
        assertThat(card).containsEntry("language", "zh").containsEntry("languages", "zh,en");
        assertThat(String.valueOf(card.get("translations"))).contains("世界各国的首都").contains("首都");

        // The feed lists it once, in the first of the taker's languages it offers.
        String mei = taker(newTaker(unique("t-q73b-mei"), "JP"));
        assertThat(feed(mei, "JP", Map.of("languages", "zh"))).filteredOn(row -> publicationId.equals(
            row.get("publicationId"))).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("name", "世界各国的首都").containsEntry("quizTitle", "首都")
                .containsEntry("displayLanguage", "zh"));
        assertThat(feed(mei, "JP", Map.of("languages", "ja,en"))).filteredOn(row -> publicationId.equals(
            row.get("publicationId"))).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("name", "Capitals of the world").containsEntry("displayLanguage", "en"));
        assertThat(feed(mei, "JP", Map.of("languages", "ja"))).noneMatch(row -> publicationId.equals(
            row.get("publicationId")));

        // Its page in Chinese: the publication's and the version's texts.
        Map<String, Object> page = items("qb.app.publication", mei, Map.of("params", Map.of("publicationId",
            publicationId, "at", clock.instant().toString(), "country", "JP", "languages", "zh"), "limit", 1))
            .getFirst();
        assertThat(page).containsEntry("name", "世界各国的首都").containsEntry("intro", "两个首都。")
            .containsEntry("displayLanguage", "zh").containsEntry("languages", "zh,en");

        // A language not offered does not start; Chinese does, and the questions are in Chinese.
        assertThat(codes(refused("QB_ATTEMPT_START", mei, body("publicationId", publicationId, "language", "ja"),
            422))).containsExactly("QB_ATTEMPT_LANGUAGE");
        Map<String, Object> started = run("QB_ATTEMPT_START", mei, body("publicationId", publicationId,
            "language", "zh"));
        String attemptId = (String) started.get("attemptId");
        assertThat((List<Map<String, Object>>) started.get("questions")).extracting(q -> q.get("stem"))
            .containsExactly("法国的首都？", "日本的首都？");
        assertThat(attempt(attemptId)).containsEntry("language", "zh");
        // Continuing keeps it, whatever is asked.
        Map<String, Object> again = run("QB_ATTEMPT_START", mei, body("publicationId", publicationId,
            "language", "en"));
        assertThat((List<Map<String, Object>>) again.get("questions")).extracting(q -> q.get("stem"))
            .containsExactly("法国的首都？", "日本的首都？");
        clock.advance(Duration.ofSeconds(1));
        assertThat(submit(mei, attemptId, answers(true, true))).containsEntry("score", 5);
        Map<String, Object> result = run("QB_ATTEMPT_RESULT", mei, body("attemptId", attemptId));
        assertThat(result).containsEntry("name", "世界各国的首都");
        assertThat((List<Map<String, Object>>) result.get("questions")).extracting(q -> q.get("stem"))
            .containsExactly("法国的首都？", "日本的首都？");

        // The taker's history names it in the attempt's language (Q73b-3), and finds it by that name.
        assertThat(items("qb.app.history", mei, Map.of("params", Map.of("q", "各国"), "limit", 10)))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("name", "世界各国的首都")
                .containsEntry("quizTitle", "首都"));

        // Without a language an attempt takes the main one; English is the base's text.
        String ren = taker(newTaker(unique("t-q73b-ren"), "JP"));
        String renAttempt = (String) run("QB_ATTEMPT_START", ren, body("publicationId", publicationId))
            .get("attemptId");
        assertThat(attempt(renAttempt)).containsEntry("language", "zh");
        String aki = taker(newTaker(unique("t-q73b-aki"), "JP"));
        Map<String, Object> english = run("QB_ATTEMPT_START", aki, body("publicationId", publicationId,
            "language", "en"));
        assertThat((List<Map<String, Object>>) english.get("questions")).extracting(q -> q.get("stem"))
            .containsExactly("Capital of France?", "Capital of Japan?");
        // One budget and one count for all of them.
        assertThat(counter(publicationId)).containsKey("completions");
    }

    @Test
    void aPublicationOfItsVersionsBaseOnlyIsAsBefore() {
        String sponsor = newSponsor(unique("s-q73b-base"));
        credit(sponsor, 100_000);
        String publicationId = livePublication(sponsor, "Plain one");
        Map<String, Object> card = card(publicationId);
        assertThat(card.get("languages")).isEqualTo(card.get("language"));
        assertThat(card.get("translations")).isNull();
        String mei = taker(newTaker(unique("t-q73b-plain"), "JP"));
        assertThat(feed(mei, "JP", Map.of("languages", String.valueOf(card.get("language")))))
            .anyMatch(row -> publicationId.equals(row.get("publicationId")));
    }
}
