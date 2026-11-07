package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.content.ContentCodes;
import com.jabiz.quizbuks.content.VideoEmbeds;
import com.jabiz.quizbuks.play.ShortsStats;
import com.jabiz.quizbuks.publishing.PublicationVersions;
import com.jabiz.runtime.publicread.PublicCounters;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-S10 (docs/quizbuks/plans/impl/F-S10.md): a vertical video (a YouTube Shorts link, or marked so on its material) is
 * frozen on the publication's card at go-live and at a switch of version; the home's template gives the shelf of
 * shorts the vertical ones only, under every other condition of the feed, with the parameters of the shelves.
 */
class ShortsIT extends PlayItSupport {

    static final String SHORTS = "https://www.youtube.com/shorts/dQw4w9WgXcQ";
    static final String WATCH = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";
    static final String BILIBILI = "https://www.bilibili.com/video/BV1GJ411x7h7";

    @Autowired
    PublicCounters counters;

    @AfterEach
    void onlyInserted() {
        assertNothingUpdated();
    }

    /** A complete quiz with a video material of {@code url} (none when null), perhaps marked vertical; its version. */
    private String videoVersion(String token, String title, String url, boolean vertical) {
        String quizId = newQuiz(token, title);
        run("QB_QUIZ_SAVE", token, body("quizId", quizId, "title", title, "timeLimitSec", 300));
        if (url != null) {
            Map<String, Object> material = body("quizId", quizId, "kind", "VIDEO_LINK", "title", "Film", "url", url);
            if (vertical) {
                material.put("vertical", true);
            }
            run("QB_MATERIAL_SAVE", token, material);
        }
        run("QB_QUESTION_SAVE", token, body("quizId", quizId, "stem", "Capital of France?", "points", 2,
            "options", List.of(body("text", "Paris", "correct", true), body("text", "Lyon"))));
        run("QB_QUESTION_SAVE", token, body("quizId", quizId, "stem", "Capital of Japan?", "points", 3,
            "options", List.of(body("text", "Tokyo", "correct", true), body("text", "Osaka"))));
        return (String) run("QB_QUIZ_PUBLISH_VERSION", token, body("quizId", quizId)).get("versionId");
    }

    /** A publication of the sponsor's aimed at everybody, live now for a week. */
    private String videoPublication(String sponsor, String name, String url, boolean vertical) {
        String token = sponsorToken(sponsor);
        String versionId = videoVersion(token, name + " quiz", url, vertical);
        Instant now = clock.instant();
        Map<String, Object> save = complete(versionId, name, now, now.plus(Duration.ofDays(7)));
        save.put("maxWinners", 1000);
        save.put("regions", List.of("GLOBAL"));
        String publicationId = (String) run("QB_PUBLICATION_SAVE", token, save).get("publicationId");
        assertThat(run("QB_PUBLICATION_SUBMIT", token, body("publicationId", publicationId)).get("status"))
            .isEqualTo("LIVE");
        return publicationId;
    }

    private Object verticalOf(String publicationId) {
        return card(publicationId).get("video_vertical");
    }

    private String contentOf(String versionId) {
        return String.valueOf(latest("qb_quiz_version_version", "version_id", "version_id = '" + versionId + "'")
            .getFirst().get("content"));
    }

    @Test
    void aShortsLinkOrTheSponsorsMarkMakesTheCardVertical() {
        autoApprove(true);
        String sponsor = newSponsor("s-fs10-card");
        credit(sponsor, 10_000);
        assertThat(verticalOf(videoPublication(sponsor, "FS10 Shorts", SHORTS, false))).isEqualTo(true);
        assertThat(verticalOf(videoPublication(sponsor, "FS10 watch", WATCH, false))).isEqualTo(false);
        assertThat(verticalOf(videoPublication(sponsor, "FS10 marked", BILIBILI, true))).isEqualTo(true);
        assertThat(verticalOf(videoPublication(sponsor, "FS10 none", null, false))).isEqualTo(false);
    }

    @Test
    void theMarkBelongsToAVideoOfTheBaseLanguageAndOnlyAMarkedOneSaysSo() {
        String token = sponsorToken(newSponsor("s-fs10-mark"));
        String quizId = newQuiz(token, "FS10 mark");
        assertThat(codes(refused("QB_MATERIAL_SAVE", token, body("quizId", quizId, "kind", "LINK", "title", "Site",
            "url", "https://example.com/", "vertical", true), 422)))
            .containsExactly(ContentCodes.MATERIAL_FIELD_NOT_ALLOWED);
        // An unmarked video's snapshot is what it was before F-S10 (its hash too); a marked one says so.
        String plain = videoVersion(token, "FS10 plain", WATCH, false);
        String marked = videoVersion(token, "FS10 marked", WATCH, true);
        assertThat(contentOf(plain)).doesNotContain("vertical");
        assertThat(contentOf(marked)).contains("\"vertical\":true");
        // Saving the unmarked material again changes nothing: none stays none.
        assertThat(latest("qb_material_version", "material_id", "quiz_id = '" + quizIdOf(plain) + "'"))
            .allSatisfy(row -> assertThat(row.get("vertical")).isNull());
    }

    private String quizIdOf(String versionId) {
        return String.valueOf(latest("qb_quiz_version_version", "version_id", "version_id = '" + versionId + "'")
            .getFirst().get("quiz_id"));
    }

    @Test
    void aSwitchOfVersionTakesTheNewVersionsVideo() {
        autoApprove(true);
        String sponsor = newSponsor("s-fs10-switch");
        credit(sponsor, 10_000);
        String publicationId = videoPublication(sponsor, "FS10 switch", WATCH, false);
        assertThat(verticalOf(publicationId)).isEqualTo(false);
        String token = sponsorToken(sponsor);
        String quizId = quizIdOf(String.valueOf(card(publicationId).get("version_id")));
        String materialId = String.valueOf(latest("qb_material_version", "material_id", "quiz_id = '" + quizId + "'")
            .getFirst().get("material_id"));
        run("QB_MATERIAL_SAVE", token, body("quizId", quizId, "materialId", materialId, "kind", "VIDEO_LINK",
            "title", "Film", "url", SHORTS));
        String second = (String) run("QB_QUIZ_PUBLISH_VERSION", token, body("quizId", quizId)).get("versionId");
        run(PublicationVersions.SWITCH, token, body("publicationId", publicationId, "versionId", second));
        assertThat(card(publicationId)).containsEntry("version_id", UUID.fromString(second))
            .containsEntry("video_vertical", true);
    }

    @Test
    void theShelfIsTheFeedsVerticalOnesWithTheParametersOfTheShelves() {
        autoApprove(true);
        String sponsor = newSponsor("s-fs10-feed");
        credit(sponsor, 10_000);
        String shorts = videoPublication(sponsor, "FS10 feed Shorts", SHORTS, false);
        String marked = videoPublication(sponsor, "FS10 feed marked Bilibili", BILIBILI, true);
        String plain = videoPublication(sponsor, "FS10 feed plain", WATCH, false);
        String token = taker(newTaker("t-fs10-feed", "JP"));

        List<Map<String, Object>> shelf = feed(token, null, Map.of("shorts", true));
        assertThat(shelf).extracting(row -> row.get("publicationId")).contains(shorts, marked).doesNotContain(plain);
        assertThat(shelf).allSatisfy(row -> assertThat(row).containsEntry("videoVertical", true)
            .containsEntry("shortsPositions", "3,12").containsEntry("shortsPerShelf", "10"));
        // The feed itself is as before: every card, the vertical ones too.
        assertThat(feed(token, null, Map.of())).extracting(row -> row.get("publicationId"))
            .contains(shorts, marked, plain);
        // The feed's filters hold for the shelf: from mainland China a YouTube Short is not listed (F-S11), a
        // Bilibili one is; a search narrows it.
        assertThat(feed(token, null, Map.of("shorts", true, "mainlandChina", true)))
            .extracting(row -> row.get("publicationId")).contains(marked).doesNotContain(shorts);
        assertThat(feed(token, null, Map.of("shorts", true, "q", "FS10 feed Shorts")))
            .extracting(row -> row.get("publicationId")).containsExactly(shorts);
        // The parameters as headquarters sets them (not controlled).
        run("PARAM_SET", admin(), body("key", "qb.feed.shorts.positions", "value", "5"));
        assertThat(feed(token, null, Map.of("shorts", true))).first()
            .satisfies(row -> assertThat(row).containsEntry("shortsPositions", "5"));
    }

    /** The openings of the vertical mode are counted by the version opened on, once a visitor and day. */
    @Test
    void theOpeningsOfTheVerticalModeAreCounted() {
        autoApprove(true);
        String sponsor = newSponsor("s-fs10-count");
        credit(sponsor, 10_000);
        String shorts = videoPublication(sponsor, "FS10 count", SHORTS, false);
        String version = String.valueOf(card(shorts).get("version_id"));
        assertThat(feed(taker(newTaker("t-fs10-count", "JP")), null, Map.of("shorts", true)))
            .filteredOn(row -> shorts.equals(row.get("publicationId"))).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("versionId", version));
        for (String browser : List.of("A", "A", "B")) {
            client.post().uri("/api/public/counters/" + ShortsStats.SHORTS_OPENS + "/" + version)
                .header("User-Agent", browser).exchange().expectStatus().isNoContent();
        }
        counters.flush().block();
        assertThat(query("SELECT hits FROM sys_public_counter_day WHERE counter_name = ? AND counter_key = ?",
            ShortsStats.SHORTS_OPENS, version)).singleElement()
            .satisfies(row -> assertThat(((Number) row.get("hits")).longValue()).isEqualTo(2));
    }

    /**
     * The migration's backfill: a card of a version with a YouTube Shorts link gets it, by the migration's own SQL,
     * which reads links as {@link VideoEmbeds#shorts} does.
     */
    @Test
    void theBackfillReadsTheVersionsAsTheApplicationDoes() throws IOException {
        autoApprove(true);
        String sponsor = newSponsor("s-fs10-backfill");
        credit(sponsor, 10_000);
        Map<String, String> links = new LinkedHashMap<>();
        for (String url : List.of(SHORTS, "https://m.youtube.com/shorts/dQw4w9WgXcQ?feature=share",
            "https://YOUTUBE.com/shorts/dQw4w9WgXcQ", WATCH, "https://youtu.be/dQw4w9WgXcQ",
            "https://www.youtube.com/embed/dQw4w9WgXcQ", BILIBILI)) {
            links.put(videoPublication(sponsor, "FS10 backfill " + links.size(), url, false), url);
        }
        String none = videoPublication(sponsor, "FS10 backfill none", null, false);
        execute("UPDATE qb_publication_card SET video_vertical = false WHERE publication_id IN ("
            + String.join(", ", java.util.Collections.nCopies(links.size() + 1, "?::uuid")) + ")",
            java.util.stream.Stream.concat(links.keySet().stream(), java.util.stream.Stream.of(none)).toArray());
        execute(backfill());
        links.forEach((publicationId, url) -> assertThat(verticalOf(publicationId)).as(url)
            .isEqualTo(VideoEmbeds.shorts(url)));
        assertThat(verticalOf(none)).isEqualTo(false);
    }

    /** The backfill statement of the migration (its number is not part of the test). */
    private static String backfill() throws IOException {
        Resource[] found = new PathMatchingResourcePatternResolver()
            .getResources("classpath*:db/migration/V*__quizbuks_shorts.sql");
        assertThat(found).hasSize(1);
        String sql = found[0].getContentAsString(StandardCharsets.UTF_8);
        int from = sql.indexOf("UPDATE qb_publication_card c");
        return sql.substring(from, sql.lastIndexOf(';'));
    }
}
