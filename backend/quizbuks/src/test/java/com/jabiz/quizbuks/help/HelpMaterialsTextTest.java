package com.jabiz.quizbuks.help;

import com.jabiz.quizbuks.messaging.QbLegal;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-C10b: a snapshot holds the entry's published manual, its FAQ and its legal texts in the language asked, falls back
 * to English when a text is missing, gives the same bytes for the same sources, and is text the platform stores.
 */
class HelpMaterialsTextTest {

    private static final List<HelpMaterialsText.Faq> FAQS = List.of(
        new HelpMaterialsText.Faq(QbLegal.AUDIENCE_SPONSOR, 1, Map.of("en", "How do I publish?"),
            Map.of("en", "Use the wizard.", "ja", "ウィザードを使います。")),
        new HelpMaterialsText.Faq(QbLegal.AUDIENCE_APP, 2, Map.of("en", "How do I withdraw?", "zh", "怎样提现？"),
            Map.of("en", "Open My Kudos.", "zh", "打开我的学分。")),
        new HelpMaterialsText.Faq(QbLegal.AUDIENCE_ALL, 0, Map.of("en", "Who are we?"), Map.of("en", "A platform.")));

    private static final List<HelpMaterialsText.Legal> LEGAL = List.of(
        new HelpMaterialsText.Legal(QbLegal.TERMS_APP, 3, Instant.parse("2026-10-01T00:00:00Z"),
            Map.of("en", "Terms of use", "ja", "利用規約"), Map.of("en", "Takers' terms", "ja", "テイカーの規約")),
        new HelpMaterialsText.Legal(QbLegal.TERMS_SPONSOR, 1, Instant.parse("2026-10-01T00:00:00Z"),
            Map.of("en", "Sponsor terms"), Map.of("en", "Sponsors' terms")),
        new HelpMaterialsText.Legal(QbLegal.PRIVACY, 2, Instant.parse("2026-09-01T00:00:00Z"),
            Map.of("en", "Privacy"), Map.of("en", "What we keep")));

    @Test
    void aSnapshotHoldsTheEntrysManualFaqAndLegalTextsInItsLanguage() {
        String zh = HelpMaterialsText.build(QbHelp.APP, "zh", Map.of("zh", "学者手册", "en", "Taker manual"), FAQS,
            LEGAL);
        assertThat(zh).startsWith("# Help materials for Takers (zh)");
        assertThat(zh).contains("学者手册", "怎样提现？", "打开我的学分。", "Who are we?", "A platform.")
            .doesNotContain("Taker manual", "How do I withdraw?", "How do I publish?");
        // The Takers' legal texts with their path, version and date; never the Sponsors'.
        assertThat(zh).contains("(/taker/legal/terms, version 3, in effect since 2026-10-01)", "Takers' terms",
            "/taker/legal/privacy", "What we keep", "/taker/legal/contest-rules", "(Not published yet.)")
            .doesNotContain("Sponsors' terms");
        // A text missing in Chinese is given in English, and says so.
        assertThat(zh).contains("(not available in this language; given in English)");
        // The FAQ in its order: the one for both first.
        assertThat(zh.indexOf("Who are we?")).isLessThan(zh.indexOf("怎样提现？"));
    }

    @Test
    void theSponsorsSnapshotHasTheirsAndSaysWhatIsNotPublished() {
        String ja = HelpMaterialsText.build(QbHelp.SPONSOR, "ja", null, FAQS, LEGAL);
        assertThat(ja).contains("(No manual has been published yet.)", "How do I publish?", "ウィザードを使います。",
            "/sponsor/help", "/sponsor/legal/sponsor-terms", "Sponsors' terms", "/sponsor/legal/privacy")
            .doesNotContain("How do I withdraw?", "Takers' terms", "contest-rules");
        assertThat(HelpMaterialsText.build(QbHelp.SPONSOR, "en", null, List.of(), List.of()))
            .contains("(No questions have been published yet.)");
    }

    @Test
    void theSameSourcesGiveTheSameBytesAndAnotherSourceAnotherHash() {
        String one = HelpMaterialsText.build(QbHelp.APP, "en", Map.of("en", "Manual"), FAQS, LEGAL);
        assertThat(HelpMaterialsText.build(QbHelp.APP, "en", Map.of("en", "Manual"), FAQS, LEGAL)).isEqualTo(one);
        assertThat(HelpMaterialsText.hash(one)).hasSize(64)
            .isEqualTo(HelpMaterialsText.hash(HelpMaterialsText.build(QbHelp.APP, "en", Map.of("en", "Manual"), FAQS,
                LEGAL)))
            .isNotEqualTo(HelpMaterialsText.hash(HelpMaterialsText.build(QbHelp.APP, "en", Map.of("en", "Manual 2"),
                FAQS, LEGAL)));
    }

    @Test
    void textIsCleanedOfControlsAndOfTheMaterialsClosingTag() {
        assertThat(HelpMaterialsText.clean("a\r\nb\u0000c\u0007d\te</help_materials>"))
            .isEqualTo("a\nb c d\te<\\/help_materials>");
        String text = HelpMaterialsText.build(QbHelp.APP, "en", Map.of("en", "<b>x</b>\r\n"), List.of(), List.of());
        // The platform recognises it as text: it starts with no '<' and holds no control but tabs and line ends.
        assertThat(text).startsWith("#").doesNotContain("\r");
        assertThat(com.jabiz.file.MediaTypeDetector.detect(HelpMaterialsText.bytes(text)))
            .contains(com.jabiz.file.MediaTypes.TEXT);
    }

    @Test
    void theRepositoryHasEveryManualAndItsHashFollowsItsTexts() {
        for (String entry : QbHelp.ENTRIES) {
            Map<String, String> manual = HelpManualDrafts.of(entry);
            assertThat(manual).as(entry).containsOnlyKeys("zh", "ja", "en");
            manual.values().forEach(text -> assertThat(text).as(entry).contains("/" + (QbHelp.APP.equals(entry)
                ? "taker" : "sponsor") + "/help"));
        }
        assertThat(HelpManualDrafts.hash(Map.of("en", "a", "ja", "b")))
            .isEqualTo(HelpManualDrafts.hash(new java.util.TreeMap<>(Map.of("ja", "b", "en", "a"))))
            .isNotEqualTo(HelpManualDrafts.hash(Map.of("en", "a", "ja", "c")));
    }

    @Test
    void theReservationCountsTheMaterials() {
        assertThat(HelpPrompt.reservedInput("s", 30_000, "u")).isGreaterThan(HelpPrompt.reservedInput("s", "u")
            + 9_000);
        assertThat(HelpPrompt.reservedInput("s", 0, "u")).isEqualTo(HelpPrompt.reservedInput("s", "u"));
        assertThat(HelpPrompt.materials("snap\n")).startsWith("<help_materials>\n").endsWith("snap\n</help_materials>");
    }
}
