package com.jabiz.quizbuks.help;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** F-C10: an AI answer keeps the site's links only, and its text loses every other address. */
class HelpAnswersTest {

    private static final Set<String> SITE = Set.of("quizbuks.example", "www.quizbuks.example");

    @Test
    void onlyTheSitesPathsAreKeptAsLinks() {
        assertThat(HelpAnswers.links(List.of("/help/wallet", "https://quizbuks.example/taker/wallet?tab=1#x",
            "http://www.quizbuks.example/help", "https://evil.example/help", "//evil.example/x", "javascript:alert(1)",
            "/help\\..\\x", "https://user@quizbuks.example/x", "/help/wallet", "ftp://quizbuks.example/x", " ", "/a b"),
            SITE, 5)).containsExactly("/help/wallet", "/taker/wallet?tab=1#x", "/help");
        // qb.help.max-links, within 0 to 10.
        List<String> twelve = java.util.stream.IntStream.rangeClosed(1, 12).mapToObj(i -> "/" + i).toList();
        assertThat(HelpAnswers.links(twelve, SITE, 5)).containsExactly("/1", "/2", "/3", "/4", "/5");
        assertThat(HelpAnswers.links(twelve, SITE, 99)).hasSize(HelpAnswers.MAX_LINKS);
        assertThat(HelpAnswers.links(twelve, SITE, 0)).isEmpty();
        assertThat(HelpAnswers.links(null, SITE, 5)).isEmpty();
        assertThat(HelpAnswers.links(List.of("https://quizbuks.example/x"), Set.of(), 5)).isEmpty();
    }

    @Test
    void otherAddressesLeaveTheTextAndMarkdownLinksKeepTheirWords() {
        String text = HelpAnswers.text("See [the FAQ](https://evil.example/faq) and [your wallet]"
            + "(https://quizbuks.example/taker/wallet). Also https://evil.example/a, www.evil.example/b and "
            + "mailto:me@evil.example; the page https://quizbuks.example/help stays as /help.", SITE);
        assertThat(text).doesNotContain("evil.example").doesNotContain("mailto")
            .contains("See the FAQ and your wallet (/taker/wallet).").contains("the page /help stays as /help.");
        assertThat(HelpAnswers.text("Chinese 见https://evil.example/中文。", SITE)).isEqualTo("Chinese 见。");
        assertThat(HelpAnswers.text(null, SITE)).isEmpty();
    }

    @Test
    void theHostAndTheStoredLinks() {
        assertThat(HelpAnswers.hostOf("https://QuizBuks.example/")).isEqualTo("quizbuks.example");
        assertThat(HelpAnswers.hostOf("@QB_BRAND_SITE_URL@")).isNull();
        assertThat(HelpAnswers.hostOf("")).isNull();
        assertThat(HelpAnswers.split(HelpAnswers.joined(List.of("/a", "/b")))).containsExactly("/a", "/b");
        assertThat(HelpAnswers.joined(List.of())).isNull();
        assertThat(HelpAnswers.clip("abcdef", 4)).isEqualTo("abc…");
        assertThat(HelpAnswers.clip("ab", 4)).isEqualTo("ab");
    }
}
