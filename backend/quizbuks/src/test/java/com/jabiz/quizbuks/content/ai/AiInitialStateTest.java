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

    private static List<String> filesWith(String text) throws IOException {
        try (Stream<Path> walk = Files.walk(Path.of("src/main/java/com/jabiz/quizbuks"))) {
            return walk.filter(path -> path.toString().endsWith(".java")).filter(path -> {
                try {
                    return Files.readString(path).replaceAll("\\s+", " ").contains(text);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }).map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void aJobIsInsertedInTwoPlacesAndOnlyTheSiteQuizOneStartsReady() throws IOException {
        // Every other job is inserted by the request flow, which starts it QUEUED (its record method).
        assertThat(filesWith("insert(QbAi.JOB")).containsExactly("AiProcesses.java", "SiteQuizProcesses.java");
        assertThat(filesWith("values.put(\"status\", QbAi.QUEUED); values.put(\"inputBytes\"")).containsExactly("AiProcesses.java");
        assertThat(filesWith("values.put(\"status\", QbAi.READY); values.put(\"candidates\", AiProcesses.candidatesJson"))
            .containsExactly("SiteQuizProcesses.java");
    }
}
