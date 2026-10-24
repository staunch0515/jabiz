package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.content.ContentCodes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The video to watch (docs/quizbuks/plans/Q19-embedded-video.md): a video material whose watching is enough to answer
 * is a YouTube or Vimeo video, one per quiz; the version keeps it, and the publication's card on the App's home carries
 * the video's player (YouTube without cookies) from when it goes on. Every other material and card has none.
 */
class WatchVideoIT extends PlayItSupport {

    private static final String WATCH = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";
    private static final String PLAYER = "https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ";

    @AfterEach
    void onlyInserted() {
        assertNothingUpdated();
    }

    private Map<String, Object> video(String quizId, String url, Object watch) {
        Map<String, Object> input = body("quizId", quizId, "kind", "VIDEO_LINK", "title", "Watch first", "url", url);
        if (watch != null) {
            input.put("watchToAnswer", watch);
        }
        return input;
    }

    @Test
    void theVideoToWatchIsOneYoutubeOrVimeoVideo() {
        String token = sponsorToken(newSponsor("s-watch-rules"));
        String quizId = newQuiz(token, "Watch rules");
        // Only a video link, and only one the App shows.
        assertThat(codes(refused("QB_MATERIAL_SAVE", token, body("quizId", quizId, "kind", "LINK", "title", "L",
            "url", WATCH, "watchToAnswer", true), 422))).containsExactly(ContentCodes.MATERIAL_FIELD_NOT_ALLOWED);
        assertThat(codes(refused("QB_MATERIAL_SAVE", token, video(quizId, "https://video.example/1", true), 422)))
            .containsExactly(ContentCodes.MATERIAL_VIDEO_NOT_EMBEDDABLE);
        String first = (String) run("QB_MATERIAL_SAVE", token, video(quizId, WATCH, true)).get("itemId");
        // A second one is refused; an ordinary video beside it is not.
        assertThat(codes(refused("QB_MATERIAL_SAVE", token, video(quizId, "https://vimeo.com/76979871", true), 422)))
            .containsExactly(ContentCodes.QUIZ_ONE_WATCH_VIDEO);
        run("QB_MATERIAL_SAVE", token, video(quizId, "https://video.example/2", null));
        // Saving the same one again keeps it; unticked it is an ordinary video, and another may be the one.
        run("QB_MATERIAL_SAVE", token, body("quizId", quizId, "materialId", first, "kind", "VIDEO_LINK", "title",
            "Watch first", "url", WATCH, "watchToAnswer", true));
        run("QB_MATERIAL_SAVE", token, body("quizId", quizId, "materialId", first, "kind", "VIDEO_LINK", "title",
            "Watch first", "url", WATCH, "watchToAnswer", false));
        run("QB_MATERIAL_SAVE", token, video(quizId, "https://vimeo.com/76979871", true));
        assertThat(query("SELECT DISTINCT ON (material_id) watch_to_answer, url FROM qb_material_version "
            + "WHERE quiz_id = ?::uuid ORDER BY material_id, version_no DESC", quizId))
            .extracting(row -> row.get("url") + "=" + row.get("watch_to_answer"))
            .containsExactlyInAnyOrder(WATCH + "=false", "https://video.example/2=false",
                "https://vimeo.com/76979871=true");
    }

    @Test
    void theCardOfAPublicationCarriesThePlayerOfItsVideo() {
        String sponsor = newSponsor("s-watch");
        credit(sponsor, 10_000);
        String token = sponsorToken(sponsor);
        String quizId = newQuiz(token, "Watch quiz");
        run("QB_QUIZ_SAVE", token, body("quizId", quizId, "title", "Watch quiz", "timeLimitSec", 300));
        run("QB_MATERIAL_SAVE", token, video(quizId, WATCH, true));
        run("QB_QUESTION_SAVE", token, body("quizId", quizId, "stem", "Capital of France?", "points", 2,
            "options", List.of(body("text", "Paris", "correct", true), body("text", "Lyon"))));
        run("QB_QUESTION_SAVE", token, body("quizId", quizId, "stem", "Capital of Japan?", "points", 3,
            "options", List.of(body("text", "Tokyo", "correct", true), body("text", "Osaka"))));
        String versionId = (String) run("QB_QUIZ_PUBLISH_VERSION", token, body("quizId", quizId)).get("versionId");
        String content = (String) query("SELECT content FROM qb_quiz_version_version WHERE version_id = ?::uuid",
            versionId).getFirst().get("content");
        assertThat(content).contains("\"url\":\"" + WATCH + "\"", "\"watchToAnswer\":true");

        Instant now = clock.instant();
        Map<String, Object> save = complete(versionId, "Watch run", now, now.plus(Duration.ofDays(7)));
        save.put("regions", List.of("GLOBAL"));
        String watched = (String) run("QB_PUBLICATION_SAVE", token, save).get("publicationId");
        run("QB_PUBLICATION_SUBMIT", token, body("publicationId", watched));
        String ordinary = livePublication(sponsor, "Plain run", Map.of("regions", List.of("GLOBAL")));

        List<Map<String, Object>> feed = items("qb.app.feed", taker(newTaker("t-watch", null)), body("params",
            body("at", clock.instant().toString()), "limit", 200));
        assertThat(feed).filteredOn(row -> row.get("publicationId").equals(watched)).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("video", PLAYER));
        assertThat(feed).filteredOn(row -> row.get("publicationId").equals(ordinary)).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("video", null));
    }
}
