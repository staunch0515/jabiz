package com.jabiz.quizbuks.operator;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-S1: no "no operator is headquarters" owner is left. Every sponsor belongs to an operator, so nothing in the
 * backend owns rows as {@code "direct"} (prospecting, certifications, and since F-M5 the audience price lists).
 */
class NoDirectOwnerTest {

    private static final Set<String> UNTIL_F_M = Set.of();

    @Test
    void theBackendHasNoDirectOwner() {
        assertThat(files(Path.of("src/main/java"), ".java")).filteredOn(file -> !UNTIL_F_M.contains(
            file.getFileName().toString())).allSatisfy(file -> assertThat(read(file)).as(file.toString())
            .doesNotContain("\"direct\""));
    }

    @Test
    void theTemplatesHaveNoDirectOwner() {
        assertThat(files(Path.of("src/main/resources/queries"), ".sql")).allSatisfy(file -> assertThat(read(file)).as(file.toString())
            .doesNotContain("'direct'").doesNotContainIgnoringCase("or direct (headquarters)"));
    }

    private static List<Path> files(Path root, String suffix) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(path -> path.toString().endsWith(suffix)).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
