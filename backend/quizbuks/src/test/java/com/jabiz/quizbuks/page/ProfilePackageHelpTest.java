package com.jabiz.quizbuks.page;

import com.jabiz.quizbuks.setup.QbParams;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The help page on the profile package (F-C11b, docs/quizbuks/help/profile-package.md) says what the product does:
 * the limit of products, the names of the menu and the buttons, the file's name. Those are numbers and words that
 * change in the code and the screens, so this test reads the help and checks them against the parameter's default and
 * the sponsor page's messages, in each of the three languages; and that the three languages say the same things in the
 * same order. A change that makes the help wrong fails here, not for a sponsor.
 */
class ProfilePackageHelpTest {

    private static final Path HELP = Path.of("../../docs/quizbuks/help/profile-package.md");
    private static final Path MESSAGES = Path.of("../../quizbuks-web/sponsor/src/messages.ts");

    /** The help's heading for each language, and the order of the sponsor page's {@code transfer} messages. */
    private static final Map<String, String> HEADINGS = Map.of("zh", "### 中文 · ", "ja", "### 日本語 · ", "en", "### English · ");
    private static final List<String> LANGUAGES = List.of("en", "zh", "ja");

    /** The help of one language: from its heading to the next heading of the same level, or the end. */
    private static Map<String, String> helpByLanguage() throws IOException {
        String text = Files.readString(HELP, StandardCharsets.UTF_8);
        Map<String, String> found = new LinkedHashMap<>();
        for (Map.Entry<String, String> heading : HEADINGS.entrySet()) {
            int start = text.indexOf(heading.getValue());
            assertThat(start).as("the " + heading.getKey() + " help").isGreaterThanOrEqualTo(0);
            int end = text.length();
            for (String other : HEADINGS.values()) {
                int next = text.indexOf(other, start + heading.getValue().length());
                if (next > start) {
                    end = Math.min(end, next);
                }
            }
            found.put(heading.getKey(), text.substring(start, end));
        }
        return found;
    }

    /** The {@code transfer} messages of a language in the sponsor page's messages (the blocks are en, zh, ja in order). */
    private static String transferMessages(String language) throws IOException {
        String text = Files.readString(MESSAGES, StandardCharsets.UTF_8);
        List<Integer> starts = new ArrayList<>();
        Matcher matcher = Pattern.compile("\\n    transfer: \\{").matcher(text);
        while (matcher.find()) {
            starts.add(matcher.start());
        }
        assertThat(starts).as("the transfer messages of the three languages").hasSize(3);
        int block = LANGUAGES.indexOf(language);
        int end = text.indexOf("\n    status: {", starts.get(block));
        return text.substring(starts.get(block), end);
    }

    private static String message(String block, String key) {
        Matcher matcher = Pattern.compile("\\b" + Pattern.quote(key) + ": '((?:[^'\\\\]|\\\\.)*)'").matcher(block);
        assertThat(matcher.find()).as("the message " + key).isTrue();
        return matcher.group(1).replace("\\'", "'");
    }

    @Test
    void theProductLimitIsTheParametersDefault() throws IOException {
        QbParams.Param param = QbParams.ALL.stream().filter(candidate -> candidate.key().equals(QbParams.PRODUCTS_MAX))
            .findFirst().orElseThrow();
        String limit = String.valueOf(param.value());
        for (Map.Entry<String, String> help : helpByLanguage().entrySet()) {
            assertThat(Pattern.compile("(?<![\\d,.])" + Pattern.quote(limit) + "(?![\\d,.])").matcher(help.getValue()).find())
                .as("the product limit " + limit + " in the " + help.getKey() + " help").isTrue();
        }
    }

    @Test
    void theParametersAndTheFileNameAreTheCodes() throws IOException {
        String text = Files.readString(HELP, StandardCharsets.UTF_8);
        List<String> keys = QbParams.ALL.stream().map(QbParams.Param::key).toList();
        Matcher named = Pattern.compile("`(qb\\.[a-z0-9.-]+)`").matcher(text);
        int found = 0;
        while (named.find()) {
            found++;
            assertThat(keys).as("the parameter " + named.group(1)).contains(named.group(1));
        }
        assertThat(found).as("the parameters the help names").isGreaterThan(0);
        for (Map.Entry<String, String> help : helpByLanguage().entrySet()) {
            assertThat(help.getValue()).as(help.getKey()).contains("." + ProfilePackage.FORMAT + ".json");
        }
    }

    @Test
    void theMenuAndTheButtonsAreNamedAsTheScreenNamesThem() throws IOException {
        for (Map.Entry<String, String> help : helpByLanguage().entrySet()) {
            String block = transferMessages(help.getKey());
            String prompt = block.substring(block.indexOf("prompt: {"));
            List<String> names = List.of(message(block, "menu"), message(block, "title"), message(block, "export"),
                message(block, "import"), message(block, "copyFix"), message(prompt, "open"), message(prompt, "copy"));
            for (String name : names) {
                assertThat(help.getValue()).as(help.getKey() + " help names \"" + name + "\" as the screen does")
                    .contains(name);
            }
        }
    }

    @Test
    void theThreeLanguagesSayTheSameThingsInTheSameOrder() throws IOException {
        Pattern label = Pattern.compile("^\\*\\*([^*]+)\\*\\*[:：]", Pattern.MULTILINE);
        int sections = -1;
        for (Map.Entry<String, String> help : helpByLanguage().entrySet()) {
            Matcher matcher = label.matcher(help.getValue());
            int count = 0;
            while (matcher.find()) {
                count++;
            }
            assertThat(count).as("the sections of the " + help.getKey() + " help").isGreaterThanOrEqualTo(9);
            if (sections < 0) {
                sections = count;
            }
            assertThat(count).as("the sections of the " + help.getKey() + " help against the first language").isEqualTo(sections);
        }
    }
}
