package com.jabiz.quizbuks.publishing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The home post's lead of an introduction (Q113b-1): Markdown's marks dropped, lines run together, cut with "…". */
class IntroLeadsTest {

    @Test
    void noIntroductionHasNoLead() {
        assertThat(IntroLeads.of(null)).isNull();
        assertThat(IntroLeads.of("")).isNull();
        assertThat(IntroLeads.of(" \n\t ")).isNull();
        assertThat(IntroLeads.of("## **")).isNull();
    }

    @Test
    void marksAreDroppedImagesEntirelyLinksDownToTheirText() {
        assertThat(IntroLeads.of("# Tea\n\nLearn **green** and _black_ tea. See [the guide](https://example.com)."
            + "\n![a cup](https://example.com/cup.png)"))
            .isEqualTo("Tea Learn green and black tea. See the guide.");
        assertThat(IntroLeads.of("> Quote with `code` and ~~strike~~")).isEqualTo("Quote with code and strike");
    }

    @Test
    void listMarksAtTheStartOfALineGoButHyphensInsideStay() {
        assertThat(IntroLeads.of("Three steps:\n- look\n+ smell\n 2. taste well-brewed tea"))
            .isEqualTo("Three steps: look smell taste well-brewed tea");
    }

    @Test
    void aTextUpToTheLengthStaysWholeALongerOneIsCutToItWithAnEllipsis() {
        String exact = "a".repeat(IntroLeads.LENGTH);
        assertThat(IntroLeads.of(exact)).isEqualTo(exact);
        String longer = IntroLeads.of("b".repeat(IntroLeads.LENGTH + 1));
        assertThat(longer).hasSize(IntroLeads.LENGTH).endsWith("…").startsWith("b".repeat(IntroLeads.LENGTH - 1));
    }

    @Test
    void theLengthCountsCharactersNotUnitsOfUtf16() {
        // Each of these is two UTF-16 units: a cut in the middle of one would leave half a character.
        String emoji = "🍵".repeat(IntroLeads.LENGTH + 5);
        String lead = IntroLeads.of(emoji);
        assertThat(lead.codePointCount(0, lead.length())).isEqualTo(IntroLeads.LENGTH);
        assertThat(lead).startsWith("🍵").endsWith("🍵…");
    }

    @Test
    void chineseAndJapaneseRunOnAsTheyAre() {
        assertThat(IntroLeads.of("古树茶和台地茶的区别很直接。\n看完短片，答 5 道题。"))
            .isEqualTo("古树茶和台地茶的区别很直接。 看完短片，答 5 道题。");
    }
}
