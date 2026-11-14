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

    /**
     * F-M12 c: the daily check names the steps a month waits for with the readiness template's own conditions, copied
     * into it: each branch's EXISTS, with the parameters renamed to the check's columns, is the same text.
     */
    @Test
    void theDailyChecksStepsAreTheReadinessBranchesWrittenTheSame() throws IOException {
        Path system = Path.of("src/main/resources/queries/qb/system");
        String readiness = code(Files.readString(system.resolve("operator-settlement-readiness.sql")));
        String check = code(Files.readString(system.resolve("recon-operator-settlement.sql")));
        // The steps are the readiness template's own: a branch added there and not to the check fails here.
        List<String> steps = new java.util.ArrayList<>();
        Matcher blocked = Pattern.compile("SELECT '(\\w+)' AS blockedBy").matcher(readiness);
        while (blocked.find()) {
            steps.add(blocked.group(1));
        }
        assertThat(steps).isNotEmpty();
        for (String step : steps) {
            Matcher a = Pattern.compile("SELECT '" + step + "' AS blockedBy WHERE EXISTS \\((.*?)\\)\\s*(?:UNION ALL|$)")
                .matcher(readiness);
            assertThat(a.find()).as(step + " in the readiness template").isTrue();
            Matcher b = Pattern.compile("CASE WHEN EXISTS \\(((?:(?!CASE WHEN).)*?)\\) THEN '" + step + "' END").matcher(check);
            assertThat(b.find()).as(step + " in the check").isTrue();
            String expected = a.group(1).replace(":operatorId", "b.r_operator").replace(":month", "b.r_month")
                .replace(":periodStart", "b.r_from").replace(":periodEnd", "b.r_to");
            assertThat(b.group(1)).as(step).isEqualTo(expected);
        }
    }

    /**
     * F-M12 c (review L7): the time a month became ready is read from the same rows the steps are: the conditions of each
     * step's subquery for it are, word for word, those of the step's branch (up to the settlement it waits for).
     */
    @Test
    void theReadyTimeReadsTheRowsTheStepsDo() throws IOException {
        String check = code(Files.readString(Path.of("src/main/resources/queries/qb/system/recon-operator-settlement.sql")));
        for (String marker : List.of("{{QbPublication.distributionUnit}} IS NOT NULL",
            "{{QbPublication.exposureUnit}} IS NOT NULL", "{{QbFeeCharge.period}} = b.r_month")) {
            java.util.Set<String> conditions = new java.util.TreeSet<>();
            int count = 0;
            for (int at = check.indexOf(marker); at >= 0; at = check.indexOf(marker, at + 1)) {
                count++;
                int from = check.lastIndexOf("WHERE ", at);
                int end = Integer.MAX_VALUE;
                for (String stop : List.of(" AND NOT EXISTS", ") t)", ") THEN")) {
                    int found = check.indexOf(stop, at);
                    if (found >= 0) {
                        end = Math.min(end, found);
                    }
                }
                conditions.add(check.substring(from, end).replaceAll("[\\s)]+$", ""));
            }
            assertThat(count).as(marker + " in the branch and in the ready time").isEqualTo(2);
            assertThat(conditions).as(marker).hasSize(1);
        }
    }

    /** The statement without comments, its white space and the aliases the copies are free to differ in. */
    private static String code(String sql) {
        return sql.lines().filter(line -> !line.strip().startsWith("--")).collect(java.util.stream.Collectors.joining(" "))
            .replaceAll("\\s+", " ").trim();
    }
}
