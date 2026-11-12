package com.jabiz.quizbuks.setup;

import com.jabiz.quizbuks.setup.FirstLaunchSwitches.Row;
import com.jabiz.quizbuks.setup.FirstLaunchSwitches.Status;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The finance page's card of the first launch's switches (F-F10b, docs/quizbuks/plans/impl/F-F10.md section 1.3) says
 * what the registry {@link FirstLaunchSwitches} says: the template {@code qb.admin.first-launch-switches} lists its
 * active rows (key, group, value, order), the extension's lists name its waiting and manual rows, and every row has
 * its name, and every group its name, in the three languages. The registry is the one list; a row added there fails
 * here until the other places have it.
 */
class FirstLaunchSwitchesTemplateTest {

    private static final Path TEMPLATE = Path.of("src/main/resources/queries/qb/admin/first-launch-switches.sql");
    private static final Path API = Path.of("admin-extension/src/first-launch-api.ts");
    private static final Path MESSAGES = Path.of("admin-extension/src/fund-switch-messages.ts");

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /** The registry's value as the template writes it: the parameter's text, or null where the declared one stands. */
    private static String text(Row row) {
        return row.value() == null ? null : String.valueOf(row.value());
    }

    private record Tuple(String key, String group, String expected, int seq) {}

    @Test
    void theTemplatesValuesAreTheRegistrysActiveRowsInItsOrder() throws IOException {
        String sql = read(TEMPLATE);
        int from = sql.indexOf("VALUES\n");
        String values = sql.substring(from, sql.indexOf("SELECT", from));
        Matcher matcher = Pattern.compile("\\('([^']+)',\\s*'([^']+)',\\s*(?:'([^']*)'|CAST\\(NULL AS text\\)),\\s*(\\d+)\\)")
            .matcher(values);
        List<Tuple> tuples = new ArrayList<>();
        while (matcher.find()) {
            tuples.add(new Tuple(matcher.group(1), matcher.group(2), matcher.group(3), Integer.parseInt(matcher.group(4))));
        }
        List<Tuple> expected = new ArrayList<>();
        for (Row row : FirstLaunchSwitches.ALL) {
            if (row.status() == Status.ACTIVE) {
                expected.add(new Tuple(row.key(), row.group(), text(row), expected.size() + 1));
            }
        }
        // The waiting rows whose key exists are read like the active ones, with the value they have at the first launch:
        // off (the declared value is false, which QB_SETUP proposes: valueFor ignores a waiting row's intent).
        for (Row row : FirstLaunchSwitches.ALL) {
            if (row.status() == Status.WAITING && FirstLaunchSwitches.KEY_BEFORE_MEANING.contains(row.key())) {
                expected.add(new Tuple(row.key(), row.group(), "false", expected.size() + 1));
            }
        }
        assertThat(tuples).as("the template's rows, key by key").isEqualTo(expected);
        // And nothing in VALUES that the pattern did not read (a row written another way).
        assertThat(values.split("\\n(?= *\\(')").length - 1).as("one line for each row").isEqualTo(expected.size());
    }

    private static List<String[]> listed(String ts, String array, int fields) {
        int start = ts.indexOf(array + ": readonly");
        assertThat(start).as("the " + array + " list").isGreaterThanOrEqualTo(0);
        String block = ts.substring(ts.indexOf('[', ts.indexOf('=', start)), ts.indexOf("\n]", start));
        Pattern pattern = Pattern.compile(fields == 3
            ? "\\{ key: '([^']+)', group: '([^']+)', waitingFor: '([^']+)' \\}"
            : "\\{ key: '([^']+)', group: '([^']+)' \\}");
        Matcher matcher = pattern.matcher(block);
        List<String[]> rows = new ArrayList<>();
        while (matcher.find()) {
            String[] row = new String[fields];
            for (int i = 0; i < fields; i++) {
                row[i] = matcher.group(i + 1);
            }
            rows.add(row);
        }
        return rows;
    }

    @Test
    void theExtensionNamesTheWaitingAndTheManualRowsOfTheRegistry() throws IOException {
        String api = read(API);
        List<String> waiting = listed(api, "WAITING_ROWS", 3).stream()
            .map(row -> row[0] + "|" + row[1] + "|" + row[2]).toList();
        assertThat(waiting).isEqualTo(FirstLaunchSwitches.ALL.stream().filter(row -> row.status() == Status.WAITING)
            .filter(row -> !FirstLaunchSwitches.KEY_BEFORE_MEANING.contains(row.key()))
            .map(row -> row.key() + "|" + row.group() + "|" + row.waitingFor()).toList());
        List<String> existing = listed(api, "KEY_BEFORE_MEANING_ROWS", 3).stream()
            .map(row -> row[0] + "|" + row[1] + "|" + row[2]).toList();
        assertThat(existing).isEqualTo(FirstLaunchSwitches.ALL.stream().filter(row -> row.status() == Status.WAITING)
            .filter(row -> FirstLaunchSwitches.KEY_BEFORE_MEANING.contains(row.key()))
            .map(row -> row.key() + "|" + row.group() + "|" + row.waitingFor()).toList());
        List<String> manual = listed(api, "MANUAL_ROWS", 2).stream().map(row -> row[0] + "|" + row[1]).toList();
        assertThat(manual).isEqualTo(FirstLaunchSwitches.ALL.stream().filter(row -> row.status() == Status.MANUAL)
            .map(row -> row.key() + "|" + row.group()).toList());
    }

    private static String nameId(String key) {
        return key.replaceAll("[.-]", "_");
    }

    @Test
    void everyRowAndEveryGroupHasItsNameInTheThreeLanguages() throws IOException {
        String ts = read(MESSAGES);
        // The blocks of the languages: en, zh, ja in order, each from its "names" and "group" objects.
        Matcher names = Pattern.compile("names: \\{(.*?)\\n    \\}", Pattern.DOTALL).matcher(ts);
        Matcher groups = Pattern.compile("group: \\{(.*?)\\n    \\}", Pattern.DOTALL).matcher(ts);
        List<String> nameBlocks = new ArrayList<>();
        while (names.find()) {
            nameBlocks.add(names.group(1));
        }
        List<String> groupBlocks = new ArrayList<>();
        while (groups.find()) {
            groupBlocks.add(groups.group(1));
        }
        assertThat(nameBlocks).as("names of the three languages").hasSize(3);
        assertThat(groupBlocks).as("groups of the three languages").hasSize(3);
        LinkedHashSet<String> allGroups = new LinkedHashSet<>();
        for (Row row : FirstLaunchSwitches.ALL) {
            allGroups.add(row.group());
            for (String block : nameBlocks) {
                assertThat(block).as("the name of %s", row.key()).containsPattern("\\b" + nameId(row.key()) + ": '[^']+'");
            }
        }
        for (String group : allGroups) {
            for (String block : groupBlocks) {
                assertThat(block).as("the name of the group %s", group).containsPattern("\\b" + group + ": '[^']+'");
            }
        }
        // No name for a row the registry does not have (a row taken out of the registry leaves its text behind).
        List<String> ids = FirstLaunchSwitches.ALL.stream().map(row -> nameId(row.key())).toList();
        for (String block : nameBlocks) {
            Matcher declared = Pattern.compile("\\n      (qb_\\w+): '").matcher(block);
            while (declared.find()) {
                assertThat(ids).as("a name for a row of the registry").contains(declared.group(1));
            }
        }
    }
}
