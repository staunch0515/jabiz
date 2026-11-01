package com.jabiz.quizbuks.content;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ContentLanguagesTest {

    private static final UUID ID = UUID.fromString("0190a000-0000-7000-8000-00000000000a");

    @Test
    void hashesTheNfcFormOfTheBaseTextAndNothingForNoText() {
        // Shared with the sponsor console's content/languages.test.ts: the same vectors.
        assertThat(ContentLanguages.sourceHash("Capital of France?")).isEqualTo("221acfeb028204b9");
        assertThat(ContentLanguages.sourceHash("é")).isEqualTo(ContentLanguages.sourceHash("é"));
        assertThat(ContentLanguages.sourceHash("é")).hasSize(16);
        assertThat(ContentLanguages.sourceHash(null)).isNull();
        assertThat(ContentLanguages.sourceHash("  ")).isNull();
    }

    @Test
    void parsesTheKeysOfEveryElement() {
        assertThat(ContentLanguages.parse("quiz.title")).get().extracting(ContentLanguages.Element::part)
            .isEqualTo(ContentLanguages.Part.TITLE);
        assertThat(ContentLanguages.parse(ContentLanguages.stem(ID))).contains(
            new ContentLanguages.Element(ContentLanguages.Part.STEM, ID));
        assertThat(ContentLanguages.parse(ContentLanguages.optionImage(ID))).contains(
            new ContentLanguages.Element(ContentLanguages.Part.OPTION_IMAGE, ID));
        assertThat(ContentLanguages.parse(ContentLanguages.optionImage(ID)).orElseThrow().text()).isFalse();
        assertThat(ContentLanguages.parse(ContentLanguages.optionText(ID)).orElseThrow().maxLength())
            .isEqualTo(ContentLimits.OPTION_LENGTH);
        assertThat(ContentLanguages.parse("question:" + ID + ".text")).isEmpty();
        assertThat(ContentLanguages.parse("option:" + ID + ".stem")).isEmpty();
        assertThat(ContentLanguages.parse("quiz.cover")).isEmpty();
        assertThat(ContentLanguages.parse(null)).isEmpty();
        assertThat(ContentLanguages.optionImage(ID).length()).isLessThanOrEqualTo(ContentLanguages.KEY_LENGTH);
    }

    @Test
    void readsAndWritesListsOfLanguages() {
        assertThat(ContentLanguages.parseList(null)).isEmpty();
        assertThat(ContentLanguages.parseList("zh, en,zh")).containsExactly("zh", "en");
        assertThat(ContentLanguages.joinList(List.of())).isNull();
        assertThat(ContentLanguages.joinList(List.of("ja", "zh"))).isEqualTo("ja,zh");
        assertThat(ContentLanguages.keysOf(ID, List.of())).containsExactly(ContentLanguages.stem(ID),
            ContentLanguages.questionImage(ID));
    }
}
