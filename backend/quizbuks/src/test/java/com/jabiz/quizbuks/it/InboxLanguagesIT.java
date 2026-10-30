package com.jabiz.quizbuks.it;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A sponsor's notice is about a publication, so the inbox shows it in the languages the taker sees (Q59b,
 * docs/quizbuks/plans/Q59-languages.md): the list and its unread count, as the home lists the publications.
 */
class InboxLanguagesIT extends InboxItSupport {

    /** A publication of a quiz in {@code language}, live now for a week in Japan. */
    private String liveIn(String sponsor, String name, String language) {
        String token = sponsorToken(sponsor);
        String quizId = (String) run("QB_QUIZ_SAVE", token, body("title", name + " quiz", "language", language,
            "timeLimitSec", 300)).get("quizId");
        run("QB_QUESTION_SAVE", token, body("quizId", quizId, "stem", "Capital of France?", "points", 2,
            "options", List.of(body("text", "Paris", "correct", true), body("text", "Lyon"))));
        run("QB_QUESTION_SAVE", token, body("quizId", quizId, "stem", "Capital of Japan?", "points", 3,
            "options", List.of(body("text", "Tokyo", "correct", true), body("text", "Osaka"))));
        String versionId = (String) run("QB_QUIZ_PUBLISH_VERSION", token, body("quizId", quizId)).get("versionId");
        Instant now = clock.instant();
        Map<String, Object> save = complete(versionId, name, now, now.plus(Duration.ofDays(7)));
        save.put("regions", List.of("JP"));
        String publicationId = (String) run("QB_PUBLICATION_SAVE", token, save).get("publicationId");
        run("QB_PUBLICATION_SUBMIT", token, body("publicationId", publicationId));
        return publicationId;
    }

    private Map<String, Object> unreadIn(String token, String languages) {
        return items("qb.app.inbox-unread", token, Map.of("params", Map.of("languages", languages))).getFirst();
    }

    @Test
    void aSponsorsNoticeReachesTheTakersOfItsPublicationsLanguage() {
        String sponsor = newSponsor(unique("s-inbox-lang"));
        credit(sponsor, 10_000);
        String zh = notice(sponsor, liveIn(sponsor, "Q59b zh", "zh"), List.of("JP"));
        String ja = notice(sponsor, liveIn(sponsor, "Q59b ja", "ja"), List.of("JP"));
        String token = taker(newTaker(unique("t-inbox-lang"), "JP"));

        assertThat(itemIds(inbox(token, "languages", "zh"))).contains(zh).doesNotContain(ja);
        assertThat(itemIds(inbox(token, "languages", "ja,en"))).contains(ja).doesNotContain(zh);
        // Without the parameter (the server's own reads): every language.
        assertThat(itemIds(inbox(token))).contains(zh, ja);
        // The unread count counts as the list.
        long both = count(unread(token), "broadcasts");
        assertThat(count(unreadIn(token, "zh"), "broadcasts")).isEqualTo(both - 1);
        assertThat(count(unreadIn(token, "en"), "broadcasts")).isEqualTo(both - 2);
    }
}
