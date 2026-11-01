package com.jabiz.quizbuks.publishing;

import com.jabiz.quizbuks.messaging.MessageTexts;
import com.jabiz.runtime.EntityInstance;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A publication's texts for a reader of a language (Q73b §3.5): its own, else the main one's, else the base. */
class PublicationLanguagesTest {

    private static EntityInstance entity(Map<String, Object> values) {
        return new EntityInstance("p", "QbPublicationCard", 1, null, new HashMap<>(values));
    }

    @Test
    void aCardsNameIsInTheReadersLanguageElseItsMainOneElseTheBase() {
        // English base, Chinese main, Japanese also offered.
        EntityInstance card = entity(Map.of("name", "Capitals", "language", "zh", "languages", "zh,en,ja",
            "translations", "{\"ja\":{\"name\":\"首都クイズ\"},\"zh\":{\"name\":\"首都\"}}"));
        assertThat(PublicationLanguages.textIn(card, "ja", PublicationLanguages.NAME)).isEqualTo("首都クイズ");
        assertThat(PublicationLanguages.textIn(card, "zh", PublicationLanguages.NAME)).isEqualTo("首都");
        // English is the base: no translation into it, so the main one's.
        assertThat(PublicationLanguages.textIn(card, "en", PublicationLanguages.NAME)).isEqualTo("首都");
        assertThat(PublicationLanguages.textIn(card, null, PublicationLanguages.NAME)).isEqualTo("首都");
        MessageTexts.Value name = MessageTexts.publicationName(card);
        assertThat(name.apply(Locale.JAPANESE)).isEqualTo("首都クイズ");
        assertThat(name.apply(Locale.ENGLISH)).isEqualTo("首都");
    }

    @Test
    void aPublicationsTextsWithTheirHashesReadTheSameAndNoneIsTheBase() {
        EntityInstance publication = entity(Map.of("name", "Capitals", "languages", "en,zh",
            "translations", "{\"zh\":{\"name\":{\"text\":\"首都\",\"hash\":\"0123456789abcdef\"}}}"));
        assertThat(PublicationLanguages.textIn(publication, "zh", PublicationLanguages.NAME)).isEqualTo("首都");
        // The main one is the base (English): its own field.
        assertThat(PublicationLanguages.textIn(publication, "ja", PublicationLanguages.NAME)).isEqualTo("Capitals");
        EntityInstance plain = entity(Map.of("name", "Plain", "language", "ja"));
        assertThat(PublicationLanguages.textIn(plain, "zh", PublicationLanguages.NAME)).isEqualTo("Plain");
        assertThat(MessageTexts.publicationName(plain).apply(Locale.CHINESE)).isEqualTo("Plain");
    }
}
