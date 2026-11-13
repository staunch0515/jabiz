package com.jabiz.quizbuks.operator;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M12 b: the steps an operator's settlement waits for are the branches of one template, and the place where F-M8
 * (exposure fees) and F-M9 (the AI monthly charge), which are not merged yet, add theirs is marked in it. Adding a
 * branch is a deliberate change of this list.
 */
class OperatorReadinessTemplateTest {

    static final Path TEMPLATE = Path.of("src/main/resources/queries/qb/system/operator-settlement-readiness.sql");

    @Test
    void theBranchesAreTheStepsThatExistAndTheExtensionPointIsMarked() throws IOException {
        String sql = Files.readString(TEMPLATE);
        // The branches, not the comment that says where the next ones go.
        String code = sql.lines().filter(line -> !line.strip().startsWith("--")).collect(java.util.stream.Collectors.joining("\n"));
        Matcher matcher = Pattern.compile("SELECT '(\\w+)' AS blockedBy").matcher(code);
        List<String> branches = new java.util.ArrayList<>();
        while (matcher.find()) {
            branches.add(matcher.group(1));
        }
        // F-M8 adds EXPOSURE and F-M9 adds AI here, when they merge.
        assertThat(branches).containsExactly("DISTRIBUTION", "INVOICE");
        assertThat(sql).contains("READINESS EXTENSION POINT (F-M12 b)").contains("F-M8").contains("F-M9");
    }
}
