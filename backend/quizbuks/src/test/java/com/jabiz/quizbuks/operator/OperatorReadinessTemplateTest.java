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
 * F-M12 b: the steps an operator's settlement waits for are the branches of one template, and the place where F-M9
 * (the AI monthly charge), which is not merged yet, add theirs is marked in it. Adding a
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
        // F-M9 adds AI here, when it merges.
        assertThat(branches).containsExactly("DISTRIBUTION", "INVOICE", "EXPOSURE");
        assertThat(sql).contains("READINESS EXTENSION POINT (F-M12 b)").contains("F-M9");
    }

    /**
     * Review L4: the expression that says which capped settlement left a fee (originMonth) is the same text in the template
     * that counts the fees and the one that takes the credit notes on them (they must agree, or a note and its fee would be
     * taken at different rates), and it compares the charge id in the C collation, as the code sorts (string order).
     */
    @Test
    void theOriginExpressionIsTheSameWhereverAFeeLeftByACappedSettlementIsRead() throws IOException {
        Path system = Path.of("src/main/resources/queries/qb/system");
        List<String> expressions = new java.util.ArrayList<>();
        for (String template : List.of("operator-settlement-charges.sql", "operator-credit-notes.sql")) {
            String sql = Files.readString(system.resolve(template)).replaceAll("\\s+", " ");
            int from = sql.indexOf("(SELECT min(s.{{QbOperatorSettlement.month}})");
            assertThat(from).as(template).isPositive();
            int to = sql.indexOf("COLLATE \"C\")))", from);
            assertThat(to).as(template).isPositive();
            expressions.add(sql.substring(from, to));
        }
        assertThat(expressions.get(0)).isEqualTo(expressions.get(1)).contains("COLLATE \"C\"");
    }
}
