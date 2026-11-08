package com.jabiz.quizbuks.asset;

import com.jabiz.entity.Violation;
import com.jabiz.quizbuks.content.ContentLimits;
import com.jabiz.quizbuks.content.ContentLanguages;
import com.jabiz.quizbuks.content.TextTranslations;
import com.jabiz.quizbuks.publishing.PublicationLanguages;
import com.jabiz.quizbuks.publishing.PublishingLimits;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** F-C1: what an asset of the library may hold, every problem at once, and what the library deduplicates by. */
class AssetRulesTest {

    private static final UUID FILE = UUID.fromString("0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b");

    private static AssetRules.Asset of(String kind) {
        return new AssetRules.Asset(kind, "Name", null, "en", null, null, null, null, null, null, null, null, null,
            null, null, null);
    }

    private static AssetRules.Asset image() {
        return with(of("IMAGE"), "image", FILE);
    }

    /** The asset with one field replaced. */
    private static AssetRules.Asset with(AssetRules.Asset a, String field, Object value) {
        return new AssetRules.Asset(
            field.equals("kind") ? (String) value : a.kind(),
            field.equals("name") ? (String) value : a.name(),
            field.equals("description") ? (String) value : a.description(),
            field.equals("language") ? (String) value : a.language(),
            field.equals("tags") ? castList(value) : a.tags(),
            field.equals("image") ? (UUID) value : a.image(),
            field.equals("pdf") ? (UUID) value : a.pdf(),
            field.equals("audio") ? (UUID) value : a.audio(),
            field.equals("url") ? (String) value : a.url(),
            field.equals("durationSec") ? (Integer) value : a.durationSec(),
            field.equals("body") ? (String) value : a.body(),
            field.equals("usage") ? (String) value : a.usage(),
            field.equals("prizeMethod") ? (String) value : a.prizeMethod(),
            field.equals("prizeDescription") ? (String) value : a.prizeDescription(),
            field.equals("digitalDelivery") ? (String) value : a.digitalDelivery(),
            field.equals("digitalSendDays") ? (Integer) value : a.digitalSendDays());
    }

    @SuppressWarnings("unchecked")
    private static List<String> castList(Object value) {
        return (List<String>) value;
    }

    private static List<String> codes(AssetRules.Asset asset) {
        return AssetRules.problems(asset, 10, 30).stream().map(Violation::ruleCode).toList();
    }

    private static List<String> fields(AssetRules.Asset asset) {
        return AssetRules.problems(asset, 10, 30).stream().map(Violation::field).toList();
    }

    @Test
    void eachKindHasItsOwnContentOnly() {
        assertThat(codes(image())).isEmpty();
        assertThat(codes(with(of("PDF"), "pdf", FILE))).isEmpty();
        assertThat(codes(with(of("AUDIO"), "audio", FILE))).isEmpty();
        assertThat(codes(with(of("LINK"), "url", "https://example.com/a"))).isEmpty();
        assertThat(codes(with(of("ARTICLE"), "body", "# Hello"))).isEmpty();
        assertThat(codes(with(with(of("TEXT"), "body", "Hi"), "usage", AssetRules.QUIZ_INTRO))).isEmpty();
        // Another kind's field: refused, every one at once.
        assertThat(fields(with(with(image(), "pdf", FILE), "body", "x"))).containsExactly("pdf", "body");
        assertThat(codes(with(image(), "url", "https://example.com"))).containsExactly(AssetCodes.FIELD_NOT_ALLOWED);
        assertThat(codes(with(with(of("LINK"), "url", "https://example.com"), "durationSec", 10)))
            .containsExactly(AssetCodes.FIELD_NOT_ALLOWED);
        // The kind's own content is needed.
        assertThat(fields(of("IMAGE"))).containsExactly("image");
        assertThat(codes(of("ARTICLE"))).containsExactly(AssetCodes.CONTENT_REQUIRED);
        assertThat(fields(of("TEXT"))).containsExactly("usage", "body");
        assertThat(codes(with(of("ARTICLE"), "body", "  "))).containsExactly(AssetCodes.CONTENT_REQUIRED);
        assertThat(codes(of("POSTER"))).containsExactly(AssetCodes.KIND_INVALID);
    }

    @Test
    void namesDescriptionsAndLanguages() {
        assertThat(codes(with(image(), "name", " "))).containsExactly(AssetCodes.NAME_BLANK);
        assertThat(codes(with(image(), "name", null))).containsExactly(AssetCodes.NAME_BLANK);
        assertThat(codes(with(image(), "name", "x".repeat(AssetRules.NAME_LENGTH)))).isEmpty();
        assertThat(codes(with(image(), "name", " " + "x".repeat(AssetRules.NAME_LENGTH) + " "))).isEmpty();
        assertThat(codes(with(image(), "name", "x".repeat(AssetRules.NAME_LENGTH + 1))))
            .containsExactly(AssetCodes.TOO_LONG);
        assertThat(AssetRules.problems(with(image(), "description", "x".repeat(ContentLimits
            .MATERIAL_DESCRIPTION_LENGTH + 1)), 10, 30).getFirst().params()).containsEntry("field", "description")
            .containsEntry("max", ContentLimits.MATERIAL_DESCRIPTION_LENGTH);
        assertThat(codes(with(image(), "language", "fr"))).containsExactly(AssetCodes.LANGUAGE_INVALID);
        assertThat(codes(with(image(), "language", null))).containsExactly(AssetCodes.LANGUAGE_INVALID);
    }

    @Test
    void textsAreAsLongAsTheFieldTheyAreCopiedInto() {
        AssetRules.Asset intro = with(of("TEXT"), "usage", AssetRules.QUIZ_INTRO);
        assertThat(codes(with(intro, "body", "x".repeat(ContentLimits.INTRO_LENGTH)))).isEmpty();
        assertThat(codes(with(intro, "body", "x".repeat(ContentLimits.INTRO_LENGTH + 1))))
            .containsExactly(AssetCodes.TOO_LONG);
        for (String usage : List.of(AssetRules.REDEEM_DESCRIPTION, AssetRules.DIGITAL_DESCRIPTION,
            AssetRules.REFERRAL_DESCRIPTION)) {
            AssetRules.Asset text = with(of("TEXT"), "usage", usage);
            assertThat(codes(with(text, "body", "x".repeat(PublishingLimits.REDEEM_DESCRIPTION_LENGTH)))).isEmpty();
            assertThat(codes(with(text, "body", "x".repeat(PublishingLimits.REDEEM_DESCRIPTION_LENGTH + 1))))
                .as(usage).containsExactly(AssetCodes.TOO_LONG);
        }
        assertThat(codes(with(with(of("TEXT"), "usage", "TAGLINE"), "body", "x")))
            .containsExactly(AssetCodes.USAGE_INVALID);
        assertThat(codes(with(of("ARTICLE"), "body", "x".repeat(ContentLimits.MATERIAL_BODY_LENGTH)))).isEmpty();
        assertThat(codes(with(of("ARTICLE"), "body", "x".repeat(ContentLimits.MATERIAL_BODY_LENGTH + 1))))
            .containsExactly(AssetCodes.TOO_LONG);
        assertThat(codes(with(of("ARTICLE"), "usage", AssetRules.QUIZ_INTRO)))
            .contains(AssetCodes.FIELD_NOT_ALLOWED);
    }

    @Test
    void linksAndVideos() {
        assertThat(codes(with(of("LINK"), "url", "javascript:alert(1)"))).containsExactly(AssetCodes.URL_FORMAT);
        assertThat(codes(with(of("LINK"), "url", "https://example.com/" + "x".repeat(ContentLimits.URL_LENGTH))))
            .containsExactly(AssetCodes.TOO_LONG);
        AssetRules.Asset video = with(of("VIDEO_LINK"), "url", "https://www.youtube.com/watch?v=dQw4w9WgXcQ");
        assertThat(codes(video)).isEmpty();
        assertThat(codes(with(of("VIDEO_LINK"), "url", "https://www.bilibili.com/video/BV1xx411c7mD"))).isEmpty();
        // F-S15: any https address is a video link (a file, another page); not https, or a site's non-video address, not.
        assertThat(codes(with(of("VIDEO_LINK"), "url", "https://example.com/video.mp4"))).isEmpty();
        assertThat(codes(with(of("VIDEO_LINK"), "url", "https://example.com/player/1"))).isEmpty();
        assertThat(codes(with(of("VIDEO_LINK"), "url", "http://example.com/video.mp4")))
            .containsExactly(AssetCodes.VIDEO_SITE);
        assertThat(codes(with(of("VIDEO_LINK"), "url", "https://www.youtube.com/@channel")))
            .containsExactly(AssetCodes.VIDEO_SITE);
        assertThat(codes(with(video, "durationSec", ContentLimits.MIN_VIDEO_SECONDS))).isEmpty();
        assertThat(codes(with(video, "durationSec", ContentLimits.MAX_VIDEO_SECONDS))).isEmpty();
        assertThat(codes(with(video, "durationSec", 0))).containsExactly(AssetCodes.DURATION_RANGE);
        assertThat(codes(with(video, "durationSec", ContentLimits.MAX_VIDEO_SECONDS + 1)))
            .containsExactly(AssetCodes.DURATION_RANGE);
    }

    @Test
    void tags() {
        assertThat(codes(with(image(), "tags", List.of(" spring ", "Sale")))).isEmpty();
        assertThat(AssetRules.tags(List.of(" spring ", "Sale"))).containsExactly("spring", "Sale");
        assertThat(AssetRules.tags(null)).isEmpty();
        assertThat(fields(with(image(), "tags", List.of("a", " ", "b")))).containsExactly("tags[1]");
        assertThat(codes(with(image(), "tags", List.of("a", " ")))).containsExactly(AssetCodes.TAG_BLANK);
        assertThat(codes(with(image(), "tags", List.of("Sale", " sale")))).containsExactly(AssetCodes.TAG_TWICE);
        assertThat(codes(with(image(), "tags", List.of("x".repeat(30))))).isEmpty();
        assertThat(codes(with(image(), "tags", List.of("x".repeat(31))))).containsExactly(AssetCodes.TAG_TOO_LONG);
        List<String> eleven = java.util.stream.IntStream.range(0, 11).mapToObj(i -> "t" + i).toList();
        assertThat(codes(with(image(), "tags", eleven))).containsExactly(AssetCodes.TOO_MANY_TAGS);
        assertThat(AssetRules.problems(with(image(), "tags", eleven), 11, 30)).isEmpty();
    }

    @Test
    void prizesAsAPublicationTakesThem() {
        AssetRules.Asset redeem = with(with(of("PRIZE"), "prizeMethod", "OFFLINE_REDEEM"), "prizeDescription",
            "A cake at our shop");
        assertThat(codes(redeem)).isEmpty();
        assertThat(fields(with(redeem, "prizeDescription", null))).containsExactly("prizeDescription");
        assertThat(codes(with(redeem, "prizeDescription", "x".repeat(PublishingLimits.REDEEM_DESCRIPTION_LENGTH + 1))))
            .containsExactly(AssetCodes.TOO_LONG);
        assertThat(codes(with(redeem, "digitalDelivery", "POOL"))).containsExactly(AssetCodes.FIELD_NOT_ALLOWED);
        assertThat(codes(with(redeem, "prizeMethod", "PLATFORM"))).containsExactly(AssetCodes.PRIZE_METHOD_INVALID);
        assertThat(fields(of("PRIZE"))).containsExactly("prizeMethod");
        // Cash says nothing more, but may describe itself.
        AssetRules.Asset cash = with(of("PRIZE"), "prizeMethod", "SPONSOR_CASH");
        assertThat(codes(cash)).isEmpty();
        assertThat(codes(with(cash, "prizeDescription", "Paid by bank transfer"))).isEmpty();
        // A digital prize says how it is delivered; only one the sponsor sends has days to send it in.
        AssetRules.Asset digital = with(with(of("PRIZE"), "prizeMethod", "DIGITAL_PRIZE"), "prizeDescription",
            "A gift code");
        assertThat(codes(digital)).containsExactly(AssetCodes.DIGITAL_DELIVERY_INVALID);
        assertThat(codes(with(digital, "digitalDelivery", "EMAIL"))).containsExactly(
            AssetCodes.DIGITAL_DELIVERY_INVALID);
        assertThat(codes(with(digital, "digitalDelivery", "POOL"))).isEmpty();
        assertThat(codes(with(with(digital, "digitalDelivery", "POOL"), "digitalSendDays", 3)))
            .containsExactly(AssetCodes.FIELD_NOT_ALLOWED);
        AssetRules.Asset sent = with(digital, "digitalDelivery", "SPONSOR_SENT");
        assertThat(codes(with(sent, "digitalSendDays", 3))).isEmpty();
        assertThat(codes(with(sent, "digitalSendDays", PublishingLimits.MAX_DIGITAL_SEND_DAYS))).isEmpty();
        assertThat(codes(with(sent, "digitalSendDays", 0))).containsExactly(AssetCodes.SEND_DAYS_RANGE);
        assertThat(codes(with(sent, "digitalSendDays", PublishingLimits.MAX_DIGITAL_SEND_DAYS + 1)))
            .containsExactly(AssetCodes.SEND_DAYS_RANGE);
        assertThat(codes(with(image(), "prizeMethod", "SPONSOR_CASH"))).containsExactly(AssetCodes.FIELD_NOT_ALLOWED);
    }

    @Test
    void translationsAsTheSponsorsShortTexts() {
        AssetRules.Asset article = with(with(of("ARTICLE"), "body", "Hello"), "description", "About");
        Map<String, String> sources = AssetRules.sources(article);
        assertThat(sources).containsEntry("name", "Name").containsEntry("description", "About")
            .containsEntry("body", "Hello").doesNotContainKey("prizeDescription");
        Map<String, Map<String, String>> given = Map.of("ja", Map.of("name", "名前", "body", "こんにちは"));
        var merged = TextTranslations.merge(given, Map.of(), sources, List.of("ja"), null);
        assertThat(merged.get("ja")).containsOnlyKeys("name", "body");
        assertThat(merged.get("ja").get("body").hash()).isEqualTo(ContentLanguages.sourceHash("Hello"));
        assertThat(AssetRules.translationProblems("en", given, merged, sources, AssetRules.maxLengths(article)))
            .isEmpty();
        // Not the asset's own language, not one the platform lacks, at most the base field's length.
        assertThat(AssetRules.translationProblems("ja", given, merged, sources, AssetRules.maxLengths(article)))
            .extracting(Violation::ruleCode).containsExactly(TextTranslations.LANGUAGES_INVALID);
        Map<String, Map<String, String>> french = Map.of("fr", Map.of("name", "Nom"));
        assertThat(AssetRules.translationProblems("en", french,
            TextTranslations.merge(french, Map.of(), sources, List.of("fr"), null), sources,
            AssetRules.maxLengths(article))).extracting(Violation::ruleCode)
            .containsExactly(TextTranslations.LANGUAGES_INVALID);
        Map<String, Map<String, String>> tooLong = Map.of("ja", Map.of("name", "x".repeat(AssetRules.NAME_LENGTH + 1)));
        assertThat(AssetRules.translationProblems("en", tooLong,
            TextTranslations.merge(tooLong, Map.of(), sources, List.of("ja"), null), sources,
            AssetRules.maxLengths(article))).extracting(Violation::ruleCode)
            .containsExactly(TextTranslations.TOO_LONG);
        // A stored translation of an older text is out of date until translated again or confirmed.
        Map<String, Map<String, PublicationLanguages.Text>> stored = Map.of("ja", Map.of("body",
            new PublicationLanguages.Text("こんにちは", ContentLanguages.sourceHash("Hi"))));
        var kept = TextTranslations.merge(null, stored, sources, List.of("ja"), null);
        assertThat(AssetRules.translationProblems("en", null, kept, sources, AssetRules.maxLengths(article)))
            .extracting(Violation::ruleCode).containsExactly(TextTranslations.STALE);
        // A prize's description is translated; a text's body as long as its use allows.
        assertThat(AssetRules.sources(with(with(of("PRIZE"), "prizeMethod", "OFFLINE_REDEEM"), "prizeDescription",
            "Cake"))).containsEntry("prizeDescription", "Cake").doesNotContainKey("body");
        assertThat(AssetRules.maxLengths(with(of("TEXT"), "usage", AssetRules.REDEEM_DESCRIPTION)))
            .containsEntry("body", PublishingLimits.REDEEM_DESCRIPTION_LENGTH);
    }

    @Test
    void theHashIsTheSqlsAndTheKeysTellFilesAndTextsApart() {
        // encode(sha256(convert_to('Hello, 世界', 'UTF8')), 'hex') in PostgreSQL.
        assertThat(AssetRules.sha256("Hello, 世界"))
            .isEqualTo("a281e84c7f61393db702630c2a6807e871cd3b6896c9e56e22982d125696575c");
        assertThat(AssetRules.sha256(null)).isNull();
        AssetRules.Asset article = with(of("ARTICLE"), "body", "Hello, 世界");
        assertThat(AssetRules.contentHash(article)).isEqualTo(AssetRules.sha256("Hello, 世界"));
        // As given: no normalisation, no trimming.
        assertThat(AssetRules.contentHash(with(of("ARTICLE"), "body", "Hello, 世界 "))).isNotEqualTo(
            AssetRules.contentHash(article));
        assertThat(AssetRules.contentHash(with(of("LINK"), "url", "https://example.com/a")))
            .isEqualTo("2dce0a4c50441bfccfa9caf4b58c3cba6e06c420505dd829f0436de1aa44baac");
        assertThat(AssetRules.contentHash(image())).isNull();
        assertThat(AssetRules.contentHash(of("NONE"))).isNull();
        assertThat(AssetRules.contentHash(with(with(of("PRIZE"), "prizeMethod", "SPONSOR_CASH"), "prizeDescription",
            "Cash"))).isEqualTo(AssetRules.sha256("Cash"));

        assertThat(AssetRules.dedupeKey(image())).isEqualTo("f:" + FILE);
        assertThat(AssetRules.dedupeKey(with(of("PDF"), "pdf", FILE))).isEqualTo("f:" + FILE);
        assertThat(AssetRules.dedupeKey(article)).isEqualTo("t:ARTICLE::" + AssetRules.sha256("Hello, 世界"));
        // A text's use and a prize's method are part of what it is.
        AssetRules.Asset intro = with(with(of("TEXT"), "usage", AssetRules.QUIZ_INTRO), "body", "Hello, 世界");
        assertThat(AssetRules.dedupeKey(intro)).isEqualTo("t:TEXT:QUIZ_INTRO:" + AssetRules.sha256("Hello, 世界"))
            .isNotEqualTo(AssetRules.dedupeKey(with(intro, "usage", AssetRules.REDEEM_DESCRIPTION)));
        assertThat(AssetRules.dedupeKey(with(of("PRIZE"), "prizeMethod", "SPONSOR_CASH"))).isNull();
        assertThat(AssetRules.dedupeKey(with(with(of("PRIZE"), "prizeMethod", "SPONSOR_CASH"), "prizeDescription",
            "Cash"))).isEqualTo("t:PRIZE:SPONSOR_CASH:" + AssetRules.sha256("Cash"));
        assertThat(AssetRules.dedupeKey("IMAGE", null, null, FILE.toString(), null)).isEqualTo("f:" + FILE);
    }

    @Test
    void anItemsProblemSaysWhichItem() {
        Violation problem = AssetRules.within("items", 2, new Violation("name", AssetCodes.NAME_BLANK, "blank"));
        assertThat(problem.field()).isEqualTo("items[2].name");
        assertThat(problem.params()).containsEntry("index", 3);
        assertThat(AssetRules.within("items", 0, new Violation(null, AssetCodes.LIMIT, "x")).field())
            .isEqualTo("items[0]");
    }

    @Test
    void kinds() {
        assertThat(AssetKind.codes()).containsExactly("IMAGE", "PDF", "AUDIO", "VIDEO_LINK", "LINK", "ARTICLE", "TEXT",
            "PRIZE");
        assertThat(AssetKind.IMAGE.file()).isTrue();
        assertThat(AssetKind.TEXT.file()).isFalse();
        assertThat(AssetKind.CONTENT_FIELDS).containsAll(java.util.Arrays.stream(AssetKind.values())
            .flatMap(kind -> kind.contentFields().stream()).toList());
        assertThat(AssetKind.of("TEXT")).contains(AssetKind.TEXT);
        assertThat(AssetKind.of(null)).isEmpty();
    }

    @Test
    void whichAssetSuitsWhichPlace() {
        // A material takes the asset of its own kind: an image group an image.
        Map<String, String> materials = Map.of("IMAGES", "IMAGE", "PDF", "PDF", "AUDIO", "AUDIO", "VIDEO_LINK",
            "VIDEO_LINK", "LINK", "LINK", "ARTICLE", "ARTICLE");
        materials.forEach((material, asset) -> {
            for (String kind : AssetKind.codes()) {
                assertThat(AssetRules.suits(QbAssets.MATERIAL, material, kind, null)).as(material + " <- " + kind)
                    .isEqualTo(kind.equals(asset));
            }
        });
        // A material of another language (no kind given) or of a kind unknown takes none.
        assertThat(AssetRules.suits(QbAssets.MATERIAL, null, "IMAGE", null)).isFalse();
        assertThat(AssetRules.suits(QbAssets.MATERIAL, "TEXT", "TEXT", AssetRules.QUIZ_INTRO)).isFalse();
        assertThat(AssetRules.suits(QbAssets.MATERIAL, "PRIZE", "PRIZE", null)).isFalse();
        // The images take images only.
        for (String place : List.of(QbAssets.QUESTION_IMAGE, QbAssets.OPTION_IMAGE, QbAssets.QUIZ_COVER,
            QbAssets.PUBLICATION_COVER)) {
            for (String kind : AssetKind.codes()) {
                assertThat(AssetRules.suits(place, null, kind, null)).as(place + " <- " + kind)
                    .isEqualTo("IMAGE".equals(kind));
            }
        }
        // The texts take a text of their own use.
        Map<String, String> texts = Map.of(QbAssets.QUIZ_INTRO, AssetRules.QUIZ_INTRO,
            QbAssets.PUBLICATION_REDEEM, AssetRules.REDEEM_DESCRIPTION,
            QbAssets.PUBLICATION_DIGITAL, AssetRules.DIGITAL_DESCRIPTION,
            QbAssets.PUBLICATION_REFERRAL, AssetRules.REFERRAL_DESCRIPTION);
        texts.forEach((place, usage) -> {
            for (String other : AssetRules.USAGES) {
                assertThat(AssetRules.suits(place, null, "TEXT", other)).as(place + " <- " + other)
                    .isEqualTo(other.equals(usage));
            }
            assertThat(AssetRules.suits(place, null, "TEXT", null)).isFalse();
            assertThat(AssetRules.suits(place, null, "ARTICLE", null)).isFalse();
        });
        // The prize takes a prize card.
        for (String kind : AssetKind.codes()) {
            assertThat(AssetRules.suits(QbAssets.PUBLICATION_PRIZE, null, kind, null)).isEqualTo("PRIZE".equals(kind));
        }
        assertThat(AssetRules.suits("ELSEWHERE", null, "IMAGE", null)).isFalse();
        assertThat(AssetRules.suits(null, null, "IMAGE", null)).isFalse();
        assertThat(AssetRules.suits(QbAssets.QUIZ_COVER, null, null, null)).isFalse();
        // Every place takes something.
        assertThat(QbAssets.TARGETS).allSatisfy(place -> assertThat(AssetKind.codes().stream().anyMatch(kind ->
            AssetRules.suits(place, QbAssets.MATERIAL.equals(place) ? "IMAGES" : null, kind,
                "TEXT".equals(kind) ? texts.get(place) : null))).as(place).isTrue());
    }
}
