package com.jabiz.quizbuks;

import com.jabiz.quizbuks.content.ContentCodes;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three languages have the same texts (backend/quizbuks/CLAUDE.md section 4): the platform's startup check covers
 * the rule codes; this covers every key, and the content codes of processes as well.
 */
class MessagesTest {

    private static Properties load(String language) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = new InputStreamReader(Objects.requireNonNull(MessagesTest.class.getResourceAsStream(
            "/messages_" + language + ".properties")), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    @Test
    void everyLanguageHasEveryText() throws IOException {
        Properties en = load("en");
        for (String language : List.of("zh", "ja")) {
            assertThat(load(language).stringPropertyNames()).as(language)
                .containsExactlyInAnyOrderElementsOf(en.stringPropertyNames());
        }
        assertThat(en.stringPropertyNames()).containsAll(ContentCodes.ALL);
        assertThat(en.stringPropertyNames()).allSatisfy(key -> assertThat(en.getProperty(key)).as(key).isNotBlank());
    }
}
