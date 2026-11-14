package com.jabiz.quizbuks.it;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The monthly report's classification read from {@code qb.admin.monthly} itself (F-M14b, review L4 of #688): every
 * branch of its CASE names the sources it applies to, so a posting of a source can only fall into the categories whose
 * branches name it (or {@code OTHER}). The guard's table ({@link MonthlyKinds#CATEGORIES_BY_SOURCE}) must say exactly
 * that, source by source; before, the guard compared only the union of the categories, and a posting of a known source
 * landing in another known category went unseen. Every category of the report has its label in the three languages of
 * the administration's monthly page.
 */
class MonthlyKindsSourcesTest {

    static final Path MONTHLY = Path.of("src/main/resources/queries/qb/admin/monthly.sql");
    static final Path MESSAGES = Path.of("admin-extension/src/finance-messages.ts");

    private static final Pattern BRANCH = Pattern.compile("WHEN (.*?)THEN '([A-Z_]+)'", Pattern.DOTALL);
    private static final Pattern ONE_SOURCE = Pattern.compile("p\\.src = '([A-Za-z]+)'");
    private static final Pattern SOURCES = Pattern.compile("p\\.src IN \\(([^)]*)\\)");
    private static final Pattern VALUE = Pattern.compile("\\((\\d+), '([A-Z_]+)'\\)");

    /** The CASE of the {@code kinds} step: source to the categories of its branches. */
    static Map<String, Set<String>> branches(String sql) {
        int start = sql.indexOf("kinds AS (");
        String kase = sql.substring(start, sql.indexOf("ELSE 'OTHER'", start));
        Map<String, Set<String>> bySource = new LinkedHashMap<>();
        Matcher branch = BRANCH.matcher(kase);
        while (branch.find()) {
            String condition = branch.group(1);
            List<String> sources = new ArrayList<>();
            Matcher one = ONE_SOURCE.matcher(condition);
            while (one.find()) {
                sources.add(one.group(1));
            }
            Matcher in = SOURCES.matcher(condition);
            while (in.find()) {
                for (String source : in.group(1).split(",")) {
                    sources.add(source.trim().replace("'", ""));
                }
            }
            assertThat(sources).as("the branch of " + branch.group(2) + " names its source (p.src = … or p.src IN …)")
                .isNotEmpty();
            sources.forEach(source -> bySource.computeIfAbsent(source, s -> new LinkedHashSet<>()).add(branch.group(2)));
        }
        return bySource;
    }

    @Test
    void eachSourceFallsOnlyIntoTheCategoriesTheGuardsTableGivesIt() throws IOException {
        Map<String, Set<String>> bySource = branches(Files.readString(MONTHLY));
        assertThat(bySource.keySet()).as("sources of qb.admin.monthly's branches")
            .containsExactlyInAnyOrderElementsOf(MonthlyKinds.CATEGORIES_BY_SOURCE.keySet());
        MonthlyKinds.CATEGORIES_BY_SOURCE.forEach((source, categories) -> assertThat(bySource.get(source))
            .as("the categories qb.admin.monthly gives a posting of " + source)
            .containsExactlyInAnyOrderElementsOf(categories));
    }

    @Test
    void theReportListsEveryCategoryOnceInOrderAndOtherLast() throws IOException {
        String sql = Files.readString(MONTHLY);
        Matcher value = VALUE.matcher(sql.substring(sql.indexOf("FROM (VALUES")));
        List<String> listed = new ArrayList<>();
        int seq = 0;
        while (value.find()) {
            assertThat(Integer.parseInt(value.group(1))).as("the sequence of " + value.group(2)).isEqualTo(++seq);
            listed.add(value.group(2));
        }
        assertThat(listed).doesNotHaveDuplicates().last().isEqualTo("OTHER");
        Set<String> given = new LinkedHashSet<>();
        branches(sql).values().forEach(given::addAll);
        given.add("OTHER");
        assertThat(listed).as("a category the CASE gives but the list lacks drops its transactions")
            .containsExactlyInAnyOrderElementsOf(given);
    }

    @Test
    void everyCategoryHasItsLabelInTheThreeLanguages() throws IOException {
        String sql = Files.readString(MONTHLY);
        String messages = Files.readString(MESSAGES);
        Matcher value = VALUE.matcher(sql.substring(sql.indexOf("FROM (VALUES")));
        while (value.find()) {
            String category = value.group(2);
            assertThat(Pattern.compile("\\n\\s+" + category + ": ['\"]").matcher(messages).results().count())
                .as("labels of " + category + " in finance-messages.ts (en, zh, ja)").isEqualTo(3);
        }
    }
}
