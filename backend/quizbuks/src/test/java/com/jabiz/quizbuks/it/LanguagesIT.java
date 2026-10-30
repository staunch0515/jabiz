package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.content.ContentCodes;
import com.jabiz.quizbuks.content.QbContent;
import com.jabiz.quizbuks.settings.QbSettings;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The language of quizzes and what a taker sees (docs/quizbuks/plans/Q59-languages.md, Q59a): a quiz is written in
 * one of the platform's languages, each version keeps its own, the card the version's, and the feed lists the
 * languages the client asks for; a taker's switches are their settings.
 */
class LanguagesIT extends PlayItSupport {

    private static Map<String, Object> quizRow(String quizId) {
        return latest("qb_quiz_version", "quiz_id", "quiz_id = '" + quizId + "'").getFirst();
    }

    private static List<Map<String, Object>> versionsOf(String quizId) {
        return query("SELECT label, language FROM qb_quiz_version_version WHERE quiz_id = ?::uuid "
            + "ORDER BY quiz_version_no", quizId);
    }

    /** A complete quiz of the sponsor's in {@code language} (two questions, full score 5), not yet versioned. */
    private String quizIn(String token, String title, String language) {
        String quizId = (String) run("QB_QUIZ_SAVE", token, body("title", title, "language", language,
            "timeLimitSec", 300)).get("quizId");
        run("QB_QUESTION_SAVE", token, body("quizId", quizId, "stem", "Capital of France?", "points", 2,
            "options", List.of(body("text", "Paris", "correct", true), body("text", "Lyon"))));
        run("QB_QUESTION_SAVE", token, body("quizId", quizId, "stem", "Capital of Japan?", "points", 3,
            "options", List.of(body("text", "Tokyo", "correct", true), body("text", "Osaka"))));
        return quizId;
    }

    /** A publication of a quiz in {@code language}, live now for a week in Japan. */
    private String liveIn(String sponsor, String name, String language) {
        String token = sponsorToken(sponsor);
        String quizId = quizIn(token, name + " quiz", language);
        String versionId = (String) run("QB_QUIZ_PUBLISH_VERSION", token, body("quizId", quizId)).get("versionId");
        Instant now = clock.instant();
        Map<String, Object> save = complete(versionId, name, now, now.plus(Duration.ofDays(7)));
        String publicationId = (String) run("QB_PUBLICATION_SAVE", token, save).get("publicationId");
        assertThat(run("QB_PUBLICATION_SUBMIT", token, body("publicationId", publicationId)).get("status"))
            .isIn("LIVE", "SCHEDULED");
        return publicationId;
    }

    @Test
    void aQuizIsWrittenInOneLanguageAndEachVersionKeepsItsOwn() {
        String token = sponsorToken(newSponsor("s-q59-quiz"));
        String quizId = quizIn(token, "Q59 quiz", "zh");
        assertThat(quizRow(quizId)).containsEntry("language", "zh");
        // Saved again without a language: it keeps its own.
        run("QB_QUIZ_SAVE", token, body("quizId", quizId, "title", "Q59 quiz, renamed", "timeLimitSec", 300));
        assertThat(quizRow(quizId)).containsEntry("language", "zh");
        // Only the platform's languages.
        refused("QB_QUIZ_SAVE", token, body("quizId", quizId, "title", "Q59 quiz", "language", "fr"), 400);

        run("QB_QUIZ_PUBLISH_VERSION", token, body("quizId", quizId));
        assertThat(versionsOf(quizId)).extracting(row -> row.get("language")).containsExactly("zh");
        // Nothing changed: no new version; a new language alone is a change.
        assertThat(codes(refused("QB_QUIZ_PUBLISH_VERSION", token, body("quizId", quizId), 422)))
            .containsExactly(ContentCodes.VERSION_UNCHANGED);
        run("QB_QUIZ_SAVE", token, body("quizId", quizId, "title", "Q59 quiz, renamed", "timeLimitSec", 300,
            "language", "en"));
        run("QB_QUIZ_PUBLISH_VERSION", token, body("quizId", quizId));
        assertThat(versionsOf(quizId)).extracting(row -> row.get("language")).containsExactly("zh", "en");

        // A copy is in its source's language; a new quiz without one in a platform language.
        String copy = (String) run("QB_QUIZ_CLONE", token, body("quizId", quizId)).get("quizId");
        assertThat(quizRow(copy)).containsEntry("language", "en");
        String plain = newQuiz(token, "Q59 plain");
        assertThat(QbContent.LANGUAGES).contains((String) quizRow(plain).get("language"));
    }

    @Test
    void theFeedListsTheLanguagesAskedForAndTheCardSaysItsLanguage() {
        String sponsor = newSponsor("s-q59-feed");
        credit(sponsor, 10_000);
        String ja = liveIn(sponsor, "Q59 ja", "ja");
        String zh = liveIn(sponsor, "Q59 zh", "zh");
        String en = liveIn(sponsor, "Q59 en", "en");
        assertThat(card(zh)).containsEntry("language", "zh");
        String token = taker(newTaker("t-q59-feed", "JP"));

        java.util.function.Function<String, List<Object>> listed = languages -> feed(token, "JP",
            languages == null ? Map.of() : Map.of("languages", languages)).stream()
            .map(row -> row.get("publicationId")).filter(List.of(ja, zh, en)::contains).toList();
        assertThat(listed.apply("zh")).containsExactly(zh);
        assertThat(listed.apply("ja,en")).containsExactlyInAnyOrder(ja, en);
        assertThat(listed.apply(null)).containsExactlyInAnyOrder(ja, zh, en);
        assertThat(feed(token, "JP", Map.of("languages", "zh"))).filteredOn(row -> zh.equals(row.get("publicationId")))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("language", "zh"));
        // The publication's page (a link opens it whatever the switches) says its language.
        assertThat(items("qb.app.publication", token, Map.of("params", Map.of("at", clock.instant().toString(),
            "country", "JP", "publicationId", en), "limit", 1))).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("language", "en"));
    }

    @Test
    void aTakerSwitchesTheLanguagesTheySeeAndTheSoundStaysAsItWas() {
        String ann = newTaker("t-q59-switch", "JP");
        String token = taker(ann);
        run("QB_SETTINGS_SAVE", token, body("soundOn", false));
        // The platform's order, each once; the sound untouched.
        assertThat(run("QB_SETTINGS_SAVE", token, body("contentLanguages", List.of("en", "ja", "en"))))
            .containsEntry("soundOn", false).containsEntry("contentLanguages", List.of("ja", "en"));
        assertThat(rows(QbSettings.MY_DATASET, token)).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("contentLanguages", "ja,en"));
        assertThat(latestSettings(ann)).containsEntry("content_languages", "ja,en").containsEntry("sound_off", true);
        // None: back to the interface language.
        assertThat(run("QB_SETTINGS_SAVE", token, body("contentLanguages", List.of())))
            .containsEntry("contentLanguages", List.of());
        assertThat(latestSettings(ann)).containsEntry("content_languages", null);
    }

    private static Map<String, Object> latestSettings(String userId) {
        return query("SELECT sound_off, content_languages FROM qb_user_settings WHERE user_id = ?", userId).getFirst();
    }
}
