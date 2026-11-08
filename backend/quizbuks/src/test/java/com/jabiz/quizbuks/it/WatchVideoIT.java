package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.content.ContentCodes;
import com.jabiz.quizbuks.play.PlayCodes;
import com.jabiz.quizbuks.play.WatchRules;
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
 * the video's player (YouTube without cookies) from when it goes on. Q63: without one, the card carries the first
 * material's video the App plays, for the taker to learn from before answering; a card without such a video has none.
 * Q75 (docs/quizbuks/plans/Q75-video-playback.md): the video to watch has a duration before a version is saved; the
 * card freezes that it must be watched and how long it is; a new start needs the App's report of enough watching.
 */
class WatchVideoIT extends PlayItSupport {

    private static final String BILIBILI = "https://www.bilibili.com/video/BV1GJ411x7h7/?spm_id_from=333.1007";

    private static final String WATCH = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";
    private static final String PLAYER = "https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ";

    /**
     * F-S11: these requests come from no address (unknown to the address database), which counts as mainland China by
     * default and would refuse every start of a YouTube video; here unknown counts as Japan (ForeignVideoIT tests the
     * rule).
     */
    @org.junit.jupiter.api.BeforeEach
    void fromJapan() {
        run("PARAM_SET", admin(), body("key", com.jabiz.quizbuks.setup.QbParams.REGION_UNKNOWN_IP_COUNTRY,
            "value", "JP"));
    }

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
        // Q99: a video the App plays whose player does not say where it is cannot be the one either.
        assertThat(codes(refused("QB_MATERIAL_SAVE", token, video(quizId, BILIBILI, true), 422)))
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
        String watchId = (String) run("QB_MATERIAL_SAVE", token, video(quizId, WATCH, true)).get("itemId");
        run("QB_QUESTION_SAVE", token, body("quizId", quizId, "stem", "Capital of France?", "points", 2,
            "options", List.of(body("text", "Paris", "correct", true), body("text", "Lyon"))));
        run("QB_QUESTION_SAVE", token, body("quizId", quizId, "stem", "Capital of Japan?", "points", 3,
            "options", List.of(body("text", "Tokyo", "correct", true), body("text", "Osaka"))));
        // Q75 (D-Q75-5): a version takes the video to watch only with its duration.
        assertThat(codes(refused("QB_QUIZ_PUBLISH_VERSION", token, body("quizId", quizId), 422)))
            .containsExactly(ContentCodes.MATERIAL_DURATION_REQUIRED);
        Map<String, Object> timed = video(quizId, WATCH, true);
        timed.put("materialId", watchId);
        timed.put("durationSec", 120);
        run("QB_MATERIAL_SAVE", token, timed);
        String versionId = (String) run("QB_QUIZ_PUBLISH_VERSION", token, body("quizId", quizId)).get("versionId");
        String content = (String) query("SELECT content FROM qb_quiz_version_version WHERE version_id = ?::uuid",
            versionId).getFirst().get("content");
        assertThat(content).contains("\"url\":\"" + WATCH + "\"", "\"watchToAnswer\":true", "\"durationSec\":120");

        Instant now = clock.instant();
        Map<String, Object> save = complete(versionId, "Watch run", now, now.plus(Duration.ofDays(7)));
        save.put("regions", List.of("GLOBAL"));
        String watched = (String) run("QB_PUBLICATION_SAVE", token, save).get("publicationId");
        run("QB_PUBLICATION_SUBMIT", token, body("publicationId", watched));
        String ordinary = livePublication(sponsor, "Plain run", Map.of("regions", List.of("GLOBAL")));

        List<Map<String, Object>> feed = items("qb.app.feed", taker(newTaker("t-watch", null)), body("params",
            body("at", clock.instant().toString()), "limit", 200));
        assertThat(feed).filteredOn(row -> row.get("publicationId").equals(watched)).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("video", PLAYER).containsEntry("watchRequired", true)
                .containsEntry("videoDurationSec", 120));
        assertThat(feed).filteredOn(row -> row.get("publicationId").equals(ordinary)).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("video", null).containsEntry("watchRequired", false)
                .containsEntry("videoDurationSec", null));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> shared = (List<Map<String, Object>>) client.get()
            .uri(b -> b.path("/api/public/queries/qb.public.publication").queryParam("p.publicationId", watched).build())
            .exchange().expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody().get("items");
        assertThat(shared).singleElement().satisfies(row -> assertThat(row).containsEntry("watchRequired", true)
            .containsEntry("videoDurationSec", 120));

        // Q75 (D-Q75-4): a new start needs the App's report of enough watching (108 of the 120 seconds, or the end).
        String taker = taker(newTaker("t-watch-start", null));
        Map<String, Object> refusal = refused("QB_ATTEMPT_START", taker, body("publicationId", watched), 422);
        assertThat(codes(refusal)).containsExactly(PlayCodes.WATCH_REQUIRED);
        // The refusal says how much is still to watch (the message carries the shortfall; params are not returned).
        assertThat(String.valueOf(violations(refusal).getFirst().get("message"))).contains("108");
        refusal = refused("QB_ATTEMPT_START", taker, body("publicationId", watched,
            "playback", body("status", "REPORTED", "watchedSec", 50, "ended", false)), 422);
        assertThat(String.valueOf(violations(refusal).getFirst().get("message"))).contains("58");
        Map<String, Object> started = run("QB_ATTEMPT_START", taker, body("publicationId", watched,
            "playback", body("status", "REPORTED", "watchedSec", 108, "ended", false, "reportedDurationSec", 121)));
        assertThat(started).containsEntry("resumed", false);
        // Continuing asks nothing: a start without a report returns the attempt in progress.
        assertThat(run("QB_ATTEMPT_START", taker, body("publicationId", watched))).containsEntry("resumed", true);
        // The end counts whatever was watched; a player that could not be read is let through and marked (D-Q75-6).
        String ended = taker(newTaker("t-watch-ended", null));
        run("QB_ATTEMPT_START", ended, body("publicationId", watched,
            "playback", body("watchedSec", 10, "ended", true)));
        String unknown = taker(newTaker("t-watch-unknown", null));
        run("QB_ATTEMPT_START", unknown, body("publicationId", watched, "playback", body("status", "UNKNOWN")));
        // The report is recorded on the attempt (D-Q75-9); an ordinary publication records nothing.
        String plain = taker(newTaker("t-watch-plain", null));
        run("QB_ATTEMPT_START", plain, body("publicationId", ordinary,
            "playback", body("status", "REPORTED", "watchedSec", 5, "ended", true)));
        assertThat(query("SELECT publication_id::text AS p, watched_sec, video_ended, playback_status FROM qb_attempt "
            + "WHERE publication_id IN (?::uuid, ?::uuid) ORDER BY started_at", watched, ordinary))
            .extracting(row -> row.get("watched_sec") + "|" + row.get("video_ended") + "|" + row.get("playback_status"))
            .containsExactly("108|false|REPORTED", "10|true|REPORTED", "null|null|UNKNOWN", "null|null|null");
        assertThat(WatchRules.needed(120)).isEqualTo(108);
    }

    /**
     * F-S15: a video file on any https host plays in the App's own {@code <video>}, which says where it is: it can be
     * the video to watch, the card carries its address, and a start needs the App's report as for YouTube (the same
     * report, WatchRules). A page of another site cannot be the one; neither can a file over http.
     */
    @Test
    void aVideoFileCanBeTheOneToWatch() {
        String file = "https://videos.staging.quizbuks.com/quizbuks-demo-30s.mp4?sig=abc";
        String sponsor = newSponsor("s-watch-file");
        credit(sponsor, 10_000);
        String token = sponsorToken(sponsor);
        String quizId = newQuiz(token, "Video file quiz");
        run("QB_QUIZ_SAVE", token, body("quizId", quizId, "title", "Video file quiz", "timeLimitSec", 300));
        for (String other : List.of("https://videos.staging.quizbuks.com/player/1",
            "http://videos.staging.quizbuks.com/quizbuks-demo-30s.mp4",
            "https://videos.staging.quizbuks.com:8443/quizbuks-demo-30s.mp4")) {
            assertThat(codes(refused("QB_MATERIAL_SAVE", token, video(quizId, other, true), 422))).as(other)
                .containsExactly(ContentCodes.MATERIAL_VIDEO_NOT_EMBEDDABLE);
        }
        Map<String, Object> timed = video(quizId, file, true);
        timed.put("durationSec", 30);
        run("QB_MATERIAL_SAVE", token, timed);
        run("QB_QUESTION_SAVE", token, body("quizId", quizId, "stem", "Capital of France?", "points", 2,
            "options", List.of(body("text", "Paris", "correct", true), body("text", "Lyon"))));
        run("QB_QUESTION_SAVE", token, body("quizId", quizId, "stem", "Capital of Japan?", "points", 3,
            "options", List.of(body("text", "Tokyo", "correct", true), body("text", "Osaka"))));
        String versionId = (String) run("QB_QUIZ_PUBLISH_VERSION", token, body("quizId", quizId)).get("versionId");
        Instant now = clock.instant();
        Map<String, Object> save = complete(versionId, "Video file run", now, now.plus(Duration.ofDays(7)));
        save.put("regions", List.of("GLOBAL"));
        String publicationId = (String) run("QB_PUBLICATION_SAVE", token, save).get("publicationId");
        run("QB_PUBLICATION_SUBMIT", token, body("publicationId", publicationId));

        List<Map<String, Object>> feed = items("qb.app.feed", taker(newTaker("t-watch-file", null)), body("params",
            body("at", clock.instant().toString()), "limit", 200));
        assertThat(feed).filteredOn(row -> row.get("publicationId").equals(publicationId)).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("video", file).containsEntry("watchRequired", true)
                .containsEntry("videoDurationSec", 30));
        String taker = taker(newTaker("t-watch-file-start", null));
        assertThat(codes(refused("QB_ATTEMPT_START", taker, body("publicationId", publicationId,
            "playback", body("status", "REPORTED", "watchedSec", 20, "ended", false)), 422)))
            .containsExactly(PlayCodes.WATCH_REQUIRED);
        assertThat(run("QB_ATTEMPT_START", taker, body("publicationId", publicationId, "playback",
            body("status", "REPORTED", "watchedSec", 27, "ended", false, "reportedDurationSec", 30))))
            .containsEntry("resumed", false);
    }

    /**
     * F-S15: without a video to watch, the card takes the first video of the seven sites or a video file; a page of
     * another site (which may refuse to be framed) only when there is nothing else, and a long address whole.
     */
    @Test
    void aPageOfAnotherSiteComesLastOnTheCard() {
        String sponsor = newSponsor("s-watch-page");
        credit(sponsor, 10_000);
        String longFile = "https://cdn.example.com/" + "a".repeat(150) + "/clip.webm";
        assertThat(cardVideoOf(sponsor, "Page first", List.of("https://player.example.com/embed/1", longFile)))
            .isEqualTo(longFile);
        assertThat(cardVideoOf(sponsor, "Page only", List.of("http://cdn.example.com/x.mp4",
            "https://player.example.com/embed/1"))).isEqualTo("https://player.example.com/embed/1");
    }

    private Object cardVideoOf(String sponsor, String title, List<String> links) {
        String token = sponsorToken(sponsor);
        String quizId = newQuiz(token, title);
        run("QB_QUIZ_SAVE", token, body("quizId", quizId, "title", title, "timeLimitSec", 300));
        for (String link : links) {
            run("QB_MATERIAL_SAVE", token, video(quizId, link, null));
        }
        run("QB_QUESTION_SAVE", token, body("quizId", quizId, "stem", "Capital of France?", "points", 2,
            "options", List.of(body("text", "Paris", "correct", true), body("text", "Lyon"))));
        run("QB_QUESTION_SAVE", token, body("quizId", quizId, "stem", "Capital of Japan?", "points", 3,
            "options", List.of(body("text", "Tokyo", "correct", true), body("text", "Osaka"))));
        String versionId = (String) run("QB_QUIZ_PUBLISH_VERSION", token, body("quizId", quizId)).get("versionId");
        Instant now = clock.instant();
        Map<String, Object> save = complete(versionId, title, now, now.plus(Duration.ofDays(7)));
        save.put("regions", List.of("GLOBAL"));
        String publicationId = (String) run("QB_PUBLICATION_SAVE", token, save).get("publicationId");
        run("QB_PUBLICATION_SUBMIT", token, body("publicationId", publicationId));
        return query("SELECT video FROM qb_publication_card WHERE publication_id = ?::uuid", publicationId)
            .getFirst().get("video");
    }

    @Test
    void withoutAVideoToWatchTheCardCarriesTheFirstVideoTheAppPlays() {
        String sponsor = newSponsor("s-watch-learn");
        credit(sponsor, 10_000);
        String token = sponsorToken(sponsor);
        String quizId = newQuiz(token, "Learn quiz");
        run("QB_QUIZ_SAVE", token, body("quizId", quizId, "title", "Learn quiz", "timeLimitSec", 300));
        // A video the App cannot play comes first: it is passed over for the one it plays.
        run("QB_MATERIAL_SAVE", token, video(quizId, "https://video.example/learn", null));
        run("QB_MATERIAL_SAVE", token, video(quizId, "https://vimeo.com/76979871", null));
        run("QB_QUESTION_SAVE", token, body("quizId", quizId, "stem", "Capital of France?", "points", 2,
            "options", List.of(body("text", "Paris", "correct", true), body("text", "Lyon"))));
        run("QB_QUESTION_SAVE", token, body("quizId", quizId, "stem", "Capital of Japan?", "points", 3,
            "options", List.of(body("text", "Tokyo", "correct", true), body("text", "Osaka"))));
        String versionId = (String) run("QB_QUIZ_PUBLISH_VERSION", token, body("quizId", quizId)).get("versionId");
        Instant now = clock.instant();
        Map<String, Object> save = complete(versionId, "Learn run", now, now.plus(Duration.ofDays(7)));
        save.put("regions", List.of("GLOBAL"));
        String learn = (String) run("QB_PUBLICATION_SAVE", token, save).get("publicationId");
        run("QB_PUBLICATION_SUBMIT", token, body("publicationId", learn));

        List<Map<String, Object>> feed = items("qb.app.feed", taker(newTaker("t-watch-learn", null)), body("params",
            body("at", clock.instant().toString()), "limit", 200));
        assertThat(feed).filteredOn(row -> row.get("publicationId").equals(learn)).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("video", "https://player.vimeo.com/video/76979871")
                .containsEntry("watchRequired", false));
        // Q75: an ordinary video asks for no watching, whatever is reported.
        run("QB_ATTEMPT_START", taker(newTaker("t-watch-learn-start", null)), body("publicationId", learn));
    }

    /** Q99 (D-Q99-1, D-Q99-2): a video of another site is the card's video too, never one to watch. */
    @Test
    void aVideoOfAnotherSiteIsTheCardsVideoAndAsksForNoWatching() {
        String sponsor = newSponsor("s-watch-other-site");
        credit(sponsor, 10_000);
        String token = sponsorToken(sponsor);
        String quizId = newQuiz(token, "Other site quiz");
        run("QB_QUIZ_SAVE", token, body("quizId", quizId, "title", "Other site quiz", "timeLimitSec", 300));
        run("QB_MATERIAL_SAVE", token, video(quizId, BILIBILI, null));
        run("QB_QUESTION_SAVE", token, body("quizId", quizId, "stem", "Capital of France?", "points", 2,
            "options", List.of(body("text", "Paris", "correct", true), body("text", "Lyon"))));
        run("QB_QUESTION_SAVE", token, body("quizId", quizId, "stem", "Capital of Japan?", "points", 3,
            "options", List.of(body("text", "Tokyo", "correct", true), body("text", "Osaka"))));
        String versionId = (String) run("QB_QUIZ_PUBLISH_VERSION", token, body("quizId", quizId)).get("versionId");
        Instant now = clock.instant();
        Map<String, Object> save = complete(versionId, "Other site run", now, now.plus(Duration.ofDays(7)));
        save.put("regions", List.of("GLOBAL"));
        String publicationId = (String) run("QB_PUBLICATION_SAVE", token, save).get("publicationId");
        run("QB_PUBLICATION_SUBMIT", token, body("publicationId", publicationId));

        List<Map<String, Object>> feed = items("qb.app.feed", taker(newTaker("t-watch-other-site", null)),
            body("params", body("at", clock.instant().toString()), "limit", 200));
        assertThat(feed).filteredOn(row -> row.get("publicationId").equals(publicationId)).singleElement()
            .satisfies(row -> assertThat(row)
                .containsEntry("video", "https://player.bilibili.com/player.html?bvid=BV1GJ411x7h7&autoplay=0")
                .containsEntry("watchRequired", false));
        run("QB_ATTEMPT_START", taker(newTaker("t-watch-other-site-start", null)), body("publicationId",
            publicationId));
    }

    /** Q75 (D-Q75-5): the duration belongs to a video of the base language, within one second and two hours. */
    @Test
    void theDurationIsAVideosWithinItsRange() {
        String token = sponsorToken(newSponsor("s-watch-duration"));
        String quizId = newQuiz(token, "Duration rules");
        assertThat(codes(refused("QB_MATERIAL_SAVE", token, body("quizId", quizId, "kind", "LINK", "title", "L",
            "url", WATCH, "durationSec", 60), 422))).containsExactly(ContentCodes.MATERIAL_FIELD_NOT_ALLOWED);
        for (int seconds : new int[] {0, 7_201}) {
            Map<String, Object> input = video(quizId, WATCH, null);
            input.put("durationSec", seconds);
            assertThat(codes(refused("QB_MATERIAL_SAVE", token, input, 422)))
                .containsExactly(ContentCodes.MATERIAL_DURATION_RANGE);
        }
        // An ordinary video keeps a duration too (it does not need one); the video to watch may be saved without one
        // while the draft is edited.
        Map<String, Object> plain = video(quizId, "https://vimeo.com/76979871", null);
        plain.put("durationSec", 7_200);
        run("QB_MATERIAL_SAVE", token, plain);
        run("QB_MATERIAL_SAVE", token, video(quizId, WATCH, true));
        assertThat(query("SELECT DISTINCT ON (material_id) url, duration_sec FROM qb_material_version "
            + "WHERE quiz_id = ?::uuid ORDER BY material_id, version_no DESC", quizId))
            .extracting(row -> row.get("url") + "=" + row.get("duration_sec"))
            .containsExactlyInAnyOrder("https://vimeo.com/76979871=7200", WATCH + "=null");
    }
}
