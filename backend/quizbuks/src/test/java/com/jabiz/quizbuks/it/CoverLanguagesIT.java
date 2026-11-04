package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.content.ContentLanguages;
import com.jabiz.quizbuks.content.QbContent;
import com.jabiz.quizbuks.publishing.PublishingCodes;
import com.jabiz.runtime.test.FileSamples;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers per language (docs/quizbuks/plans/Q98-covers.md, Q98b): a quiz's own cover in one of its other languages
 * (the translation element {@code quiz.cover}) goes into its version; a publication may have its own cover in each
 * language it offers besides its base; the card keeps, per language, the publication's own in that language, else the
 * publication's own, else the version's in that language, else the version's; the home and the page show the cover of
 * the language they are shown in.
 */
class CoverLanguagesIT extends PlayItSupport {

    @BeforeEach
    void reviewedByDefault() {
        autoApprove(true);
    }

    private String picture(String token, String name) {
        return (String) upload(QbContent.IMAGE_POLICY, FileSamples.jpeg(400, 300), name, "image/jpeg", token)
            .expectStatus().isCreated().expectBody(MAP).returnResult().getResponseBody().get("fileId");
    }

    /**
     * A quiz in English with a complete Chinese translation, its cover {@code cover} and in Chinese {@code zhCover}
     * (none: the base's); returns its version's key.
     */
    @SuppressWarnings("unchecked")
    private String bilingualVersion(String token, String cover, String zhCover) {
        String quizId = (String) run("QB_QUIZ_SAVE", token, body("title", "Capitals", "language", "en",
            "intro", "Two capitals.", "timeLimitSec", 300, "cover", cover)).get("quizId");
        Map<String, Object> q1 = run("QB_QUESTION_SAVE", token, body("quizId", quizId, "stem", "Capital of France?",
            "points", 2, "options", List.of(body("text", "Paris", "correct", true), body("text", "Lyon"))));
        Map<String, Object> q2 = run("QB_QUESTION_SAVE", token, body("quizId", quizId, "stem", "Capital of Japan?",
            "points", 3, "options", List.of(body("text", "Tokyo", "correct", true), body("text", "Osaka"))));
        List<String> o1 = (List<String>) q1.get("partIds");
        List<String> o2 = (List<String>) q2.get("partIds");
        run("QB_QUIZ_LANGUAGES_SET", token, body("quizId", quizId, "languages", List.of("zh")));
        List<Map<String, Object>> entries = new java.util.ArrayList<>(List.of(
            body("key", ContentLanguages.TITLE, "text", "首都"),
            body("key", ContentLanguages.INTRO, "text", "两个首都。"),
            body("key", ContentLanguages.stem(q1.get("itemId")), "text", "法国的首都？"),
            body("key", ContentLanguages.optionText(o1.get(0)), "text", "巴黎"),
            body("key", ContentLanguages.optionText(o1.get(1)), "text", "里昂"),
            body("key", ContentLanguages.stem(q2.get("itemId")), "text", "日本的首都？"),
            body("key", ContentLanguages.optionText(o2.get(0)), "text", "东京"),
            body("key", ContentLanguages.optionText(o2.get(1)), "text", "大阪")));
        if (zhCover != null) {
            entries.add(body("key", ContentLanguages.COVER, "image", zhCover));
        }
        run("QB_TRANSLATION_SAVE", token, body("quizId", quizId, "language", "zh", "entries", entries));
        Map<String, Object> version = run("QB_QUIZ_PUBLISH_VERSION", token, body("quizId", quizId));
        assertThat(version.get("languages")).isEqualTo(List.of("en", "zh"));
        return (String) version.get("versionId");
    }

    /** A complete save of a publication of the version in English and Chinese. */
    private Map<String, Object> bilingualSave(String versionId, String name) {
        Instant now = clock.instant();
        Map<String, Object> save = complete(versionId, name, now, now.plus(Duration.ofDays(7)));
        save.put("regions", List.of("GLOBAL"));
        save.put("maxWinners", 1000);
        save.put("languages", List.of("en", "zh"));
        save.put("translations", Map.of("zh", Map.of("name", "中文名称")));
        return save;
    }

    private String live(String token, Map<String, Object> save) {
        String publicationId = (String) run("QB_PUBLICATION_SAVE", token, save).get("publicationId");
        assertThat(submit(token, publicationId).get("status")).isEqualTo("LIVE");
        return publicationId;
    }

    private Object inFeed(String token, String publicationId, String languages) {
        return feed(token, "JP", Map.of("languages", languages)).stream()
            .filter(item -> publicationId.equals(item.get("publicationId"))).findFirst().orElseThrow().get("cover");
    }

    private Object onPage(String token, String publicationId, String languages) {
        return items("qb.app.publication", token, Map.of("params", Map.of("publicationId", publicationId, "at",
            clock.instant().toString(), "country", "JP", "languages", languages), "limit", 1)).getFirst()
            .get("cover");
    }

    @Test
    void theVersionsCoverOfALanguageIsShownInIt() {
        String sponsor = newSponsor(unique("s-q98-version"));
        credit(sponsor, 100_000);
        String token = sponsorToken(sponsor);
        String cover = picture(token, "en.jpg");
        String zhCover = picture(token, "zh.jpg");
        String publicationId = live(token, bilingualSave(bilingualVersion(token, cover, zhCover), "Version covers"));

        Map<String, Object> card = card(publicationId);
        assertThat(card).containsEntry("cover", cover).containsEntry("cover_zh", zhCover)
            .containsEntry("cover_en", null).containsEntry("cover_ja", null);
        String taker = taker(newTaker(unique("t-q98-version"), "JP"));
        assertThat(inFeed(taker, publicationId, "zh")).isEqualTo(zhCover);
        assertThat(inFeed(taker, publicationId, "en")).isEqualTo(cover);
        assertThat(onPage(taker, publicationId, "zh")).isEqualTo(zhCover);
        assertThat(onPage(taker, publicationId, "en")).isEqualTo(cover);

        // Without a Chinese cover of its own, the version's base cover in both.
        String plain = live(token, bilingualSave(bilingualVersion(token, cover, null), "Base cover only"));
        assertThat(card(plain)).containsEntry("cover", cover).containsEntry("cover_zh", null);
        assertThat(inFeed(taker, plain, "zh")).isEqualTo(cover);
        assertNothingUpdated();
    }

    @Test
    void aPublicationsOwnCoversComeFirst() {
        String sponsor = newSponsor(unique("s-q98-own"));
        credit(sponsor, 100_000);
        String token = sponsorToken(sponsor);
        String cover = picture(token, "en.jpg");
        String zhCover = picture(token, "zh.jpg");
        String ownCover = picture(token, "own.jpg");
        String ownZh = picture(token, "own-zh.jpg");
        String versionId = bilingualVersion(token, cover, zhCover);
        String taker = taker(newTaker(unique("t-q98-own"), "JP"));

        // Its own cover in Chinese and its own cover: each language its own.
        Map<String, Object> both = bilingualSave(versionId, "Own covers");
        both.put("cover", ownCover);
        both.put("covers", Map.of("zh", ownZh));
        String withBoth = live(token, both);
        assertThat(row(withBoth)).containsEntry("cover_zh", UUID.fromString(ownZh)).containsEntry("cover_en", null);
        assertThat(inFeed(taker, withBoth, "zh")).isEqualTo(ownZh);
        assertThat(inFeed(taker, withBoth, "en")).isEqualTo(ownCover);

        // Its own cover only: it is shown in Chinese too, before the version's Chinese cover.
        Map<String, Object> own = bilingualSave(versionId, "Own cover only");
        own.put("cover", ownCover);
        String withOwn = live(token, own);
        assertThat(card(withOwn)).containsEntry("cover", ownCover).containsEntry("cover_zh", null);
        assertThat(inFeed(taker, withOwn, "zh")).isEqualTo(ownCover);

        // Its own cover in Chinese only: the version's cover in English.
        Map<String, Object> zhOnly = bilingualSave(versionId, "Own Chinese cover only");
        zhOnly.put("covers", Map.of("zh", ownZh));
        String withZh = live(token, zhOnly);
        assertThat(inFeed(taker, withZh, "zh")).isEqualTo(ownZh);
        assertThat(inFeed(taker, withZh, "en")).isEqualTo(cover);

        // A copy keeps the covers per language.
        String copy = (String) run("QB_PUBLICATION_COPY", token, body("publicationId", withBoth))
            .get("publicationId");
        assertThat(row(copy)).containsEntry("cover_zh", UUID.fromString(ownZh));
        assertNothingUpdated();
    }

    @Test
    void aCoverPerLanguageIsTheSponsorsAndForALanguageOfferedBesidesTheBase() {
        String sponsor = newSponsor(unique("s-q98-refused"));
        credit(sponsor, 100_000);
        String token = sponsorToken(sponsor);
        String versionId = bilingualVersion(token, picture(token, "en.jpg"), null);
        String mine = picture(token, "mine.jpg");

        // Japanese is not offered, English is the base (its cover is "cover").
        Map<String, Object> save = bilingualSave(versionId, "Refused covers");
        save.put("covers", new LinkedHashMap<>(Map.of("ja", mine, "en", mine)));
        Map<String, Object> refusal = refused("QB_PUBLICATION_SAVE", token, save, 422);
        assertThat(codes(refusal)).containsOnly(PublishingCodes.COVER_LANGUAGE);

        // Another sponsor's file is no cover of this one's.
        String other = sponsorToken(newSponsor(unique("s-q98-other")));
        save.put("covers", Map.of("zh", picture(other, "theirs.jpg")));
        assertThat(codes(refused("QB_PUBLICATION_SAVE", token, save, 422))).contains("QB_CONTENT_FILE_NOT_OWN");
    }
}
