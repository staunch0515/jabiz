package com.jabiz.quizbuks.content.transfer;

import com.jabiz.quizbuks.content.QuizCompleteness;
import com.jabiz.quizbuks.content.QuizSnapshot;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The examples of docs/quizbuks/quiz-file-prompts.md, the prompts sponsors give other AI tools to write quiz files:
 * each must be read, checked and completed as an import and a version are, so that the prompt teaches what the server
 * takes. A rule changed here fails this test until the document is changed with it.
 */
class QuizFilePromptsTest {

    private static final Path DOCUMENT = Path.of("../../docs/quizbuks/quiz-file-prompts.md");
    private static final Pattern JSON_BLOCK = Pattern.compile("(?m)^```json\\n(.*?)^```", Pattern.DOTALL);

    @Test
    @SuppressWarnings("unchecked")
    void everyExampleImportsAndCanBeSavedAsAVersion() throws IOException {
        Matcher blocks = JSON_BLOCK.matcher(Files.readString(DOCUMENT, StandardCharsets.UTF_8));
        List<String> examples = new ArrayList<>();
        while (blocks.find()) {
            examples.add(blocks.group(1));
        }
        assertThat(examples).hasSize(2);
        for (String example : examples) {
            Map<String, Object> document = JsonMapper.builder().build().readValue(example, Map.class);
            QuizFileFormat.Reading reading = QuizFileFormat.read(document);
            assertThat(reading.status()).as(reading.problem()).isEqualTo(QuizFileFormat.Status.SUPPORTED);
            QuizFile file = reading.file();
            assertThat(QuizFileChecks.check(file)).isEmpty();
            assertThat(QuizFileChecks.needed(file)).isEmpty();
            QuizSnapshot draft = QuizTransferProcesses.snapshot(file, Map.of());
            assertThat(QuizCompleteness.check(draft)).isEmpty();
            // A language is complete when every base text is translated (saving a version takes only complete ones).
            file.translations().forEach((language, translation) ->
                assertThat(translation.texts().keySet()).as(language).isEqualTo(baseTexts(file)));
        }
    }

    /** The keys of the base texts a translation has to give. */
    private static Set<String> baseTexts(QuizFile file) {
        Set<String> keys = new LinkedHashSet<>();
        keys.add("title");
        if (file.intro() != null) {
            keys.add("intro");
        }
        for (int q = 0; q < file.questions().size(); q++) {
            QuizFile.Question question = file.questions().get(q);
            if (question.stem() != null) {
                keys.add("q" + (q + 1) + ".stem");
            }
            for (int o = 0; o < question.options().size(); o++) {
                if (question.options().get(o).text() != null) {
                    keys.add("q" + (q + 1) + ".o" + (o + 1) + ".text");
                }
            }
        }
        return keys;
    }
}
