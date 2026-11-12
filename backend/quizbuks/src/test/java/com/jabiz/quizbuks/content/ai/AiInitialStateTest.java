package com.jabiz.quizbuks.content.ai;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An AI job starts {@code QUEUED} and a platform job's outcome makes it {@code READY}: the one exception is the
 * questions kept next to a quiz made from a website draft ({@link SiteQuizProcesses}), whose answer already exists
 * (F-C7b-2). The state machine allows both starts, so this guard keeps every other flow from inserting a job as
 * {@code READY} (it would then be one no platform job stands behind).
 */
class AiInitialStateTest {

    /** The files with the text, each once (the text counts as often as it occurs in the file, in the order of the files). */
    private static List<String> filesWith(String text) throws IOException {
        try (Stream<Path> walk = Files.walk(Path.of("src/main/java/com/jabiz/quizbuks"))) {
            return walk.filter(path -> path.toString().endsWith(".java")).sorted().flatMap(path -> {
                try {
                    String code = Files.readString(path).replaceAll("\\s+", " ");
                    int count = 0;
                    for (int at = code.indexOf(text); at >= 0; at = code.indexOf(text, at + 1)) {
                        count++;
                    }
                    return java.util.Collections.nCopies(count, path.getFileName().toString()).stream();
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }).toList();
        }
    }

    @Test
    void aJobIsInsertedInTwoPlacesAndOnlyTheSiteQuizOneStartsReady() throws IOException {
        // Exactly once in each file: a second insert in either would be a new way to start a job.
        assertThat(filesWith("insert(QbAi.JOB")).containsExactly("AiProcesses.java", "SiteQuizProcesses.java");
        assertThat(filesWith("values.put(\"status\", QbAi.QUEUED); values.put(\"inputBytes\"")).containsExactly("AiProcesses.java");
        assertThat(filesWith("values.put(\"status\", QbAi.READY); values.put(\"candidates\", AiProcesses.candidatesJson"))
            .containsExactly("SiteQuizProcesses.java");
    }
}
