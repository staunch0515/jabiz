package com.jabiz.quizbuks.wallet;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M14b (plan F-M14 §3.1 item 4, G8): the operations guide's table of the daily checks ({@code operations.md}, the
 * section "每日核对与月报分类一览") has one row for every check the reconciliation runs, none for anything else, each
 * with the check's own level, and its heading counts what the code counts. A check added without its row, or a level
 * changed in the code only, fails here.
 */
class ReconOperationsTableTest {

    static final Path OPERATIONS = Path.of("../../docs/quizbuks/operations.md");

    /** The level as the table writes it. */
    static final Map<ReconChecks.Level, String> LEVEL_WORD = Map.of(ReconChecks.Level.DIFFERENCE, "差异",
        ReconChecks.Level.ATTENTION, "需关注", ReconChecks.Level.NOTE, "提示");

    /** The section of the table: from its heading to the next heading of the same depth. */
    static String section() throws IOException {
        String text = Files.readString(OPERATIONS);
        Matcher heading = Pattern.compile("(?m)^### 7\\.\\d+ 每日核对与月报分类一览.*$").matcher(text);
        assertThat(heading.find()).as("the section of the daily checks in operations.md").isTrue();
        int end = text.indexOf("\n### ", heading.end());
        return text.substring(heading.start(), end < 0 ? text.length() : end);
    }

    /** By code, the level column of each row of the table (a code twice keeps the count in {@code twice}). */
    static Map<String, String> rows(String section, List<String> twice) {
        Map<String, String> rows = new LinkedHashMap<>();
        Matcher row = Pattern.compile("(?m)^\\| `([A-Z][A-Z0-9_]+)` \\|[^|]*\\| ([^|]+?) \\|").matcher(section);
        while (row.find()) {
            if (rows.put(row.group(1), row.group(2).trim()) != null) {
                twice.add(row.group(1));
            }
        }
        return rows;
    }

    @Test
    void everyCheckHasOneRowWithItsLevel() throws IOException {
        List<String> twice = new java.util.ArrayList<>();
        Map<String, String> rows = rows(section(), twice);
        assertThat(twice).as("codes with more than one row").isEmpty();
        assertThat(rows.keySet()).as("the table's codes").containsExactlyInAnyOrderElementsOf(ReconChecks.ALL);
        for (String code : ReconChecks.ALL) {
            assertThat(rows.get(code)).as("the level of " + code).isEqualTo(LEVEL_WORD.get(ReconChecks.level(code)));
        }
    }

    @Test
    void theHeadingCountsWhatTheCodeCounts() throws IOException {
        Matcher counts = Pattern.compile("共 (\\d+) 项：差异 (\\d+)、需关注 (\\d+)、提示 (\\d+)").matcher(section());
        assertThat(counts.find()).as("the heading's counts (共 n 项：差异 a、需关注 b、提示 c)").isTrue();
        long attention = ReconChecks.ALL.stream().filter(c -> ReconChecks.level(c) == ReconChecks.Level.ATTENTION).count();
        long notes = ReconChecks.ALL.stream().filter(c -> ReconChecks.level(c) == ReconChecks.Level.NOTE).count();
        assertThat(List.of(counts.group(1), counts.group(2), counts.group(3), counts.group(4))).containsExactly(
            String.valueOf(ReconChecks.ALL.size()), String.valueOf(ReconChecks.ALL.size() - attention - notes),
            String.valueOf(attention), String.valueOf(notes));
    }
}
