package com.jabiz.quizbuks.page;

import com.jabiz.quizbuks.setup.QbParams;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The help page on drafting from a website (F-C7, docs/quizbuks/help/site-profile.md) says what the product does: the
 * numbers of the limits (the parameters' defaults), the name of the menu item and the buttons (the sponsor page's
 * messages), and the same things in the same order in each of the three languages. A change that makes the help wrong
 * fails here, not for a sponsor.
 */
class SiteProfileHelpTest {

    private static final Path HELP = Path.of("../../docs/quizbuks/help/site-profile.md");
    private static final Path MESSAGES = Path.of("../../quizbuks-web/sponsor/src/profile/site/messages.ts");
    private static final Map<String, String> HEADINGS = Map.of("zh", "### 中文 · ", "ja", "### 日本語 · ", "en", "### English · ");

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

    /** The messages of one language: from its {@code const} to the next. */
    private static String messages(String language) throws IOException {
        String text = Files.readString(MESSAGES, StandardCharsets.UTF_8);
        int start = text.indexOf("const " + language + " = {");
        assertThat(start).as("the " + language + " messages").isGreaterThanOrEqualTo(0);
        int end = text.indexOf("\nconst ", start + 1);
        if (end < 0) {
            end = text.indexOf("\nexport const", start);
        }
        return text.substring(start, end);
    }

    private static String message(String block, String key) {
        Matcher matcher = Pattern.compile("\\b" + Pattern.quote(key) + ": '((?:[^'\\\\]|\\\\.)*)'").matcher(block);
        assertThat(matcher.find()).as("the message " + key).isTrue();
        return matcher.group(1).replace("\\'", "'");
    }

    private static String defaultOf(String key) {
        return String.valueOf(QbParams.ALL.stream().filter(candidate -> candidate.key().equals(key)).findFirst()
            .orElseThrow().value());
    }

    @Test
    void theLimitsAreTheParametersDefaults() throws IOException {
        for (String key : List.of(QbParams.SITE_PROFILE_DAILY, QbParams.SITE_PROFILE_MONTHLY,
            QbParams.SITE_PROFILE_MAX_PAGES)) {
            String value = defaultOf(key);
            for (Map.Entry<String, String> help : helpByLanguage().entrySet()) {
                assertThat(Pattern.compile("(?<![\\d,.])" + Pattern.quote(value) + "(?![\\d,.])")
                    .matcher(help.getValue()).find()).as(key + " = " + value + " in the " + help.getKey() + " help").isTrue();
            }
        }
    }

    @Test
    void theParametersTheHelpNamesExist() throws IOException {
        List<String> keys = QbParams.ALL.stream().map(QbParams.Param::key).toList();
        Matcher named = Pattern.compile("`(qb\\.[a-z0-9.-]+)`").matcher(Files.readString(HELP, StandardCharsets.UTF_8));
        int found = 0;
        while (named.find()) {
            found++;
            assertThat(keys).as("the parameter " + named.group(1)).contains(named.group(1));
        }
        assertThat(found).isGreaterThan(0);
    }

    @Test
    void theMenuItemAndTheButtonsAreNamedAsTheScreenNamesThem() throws IOException {
        for (Map.Entry<String, String> help : helpByLanguage().entrySet()) {
            String block = messages(help.getKey());
            List<String> names = List.of(message(block, "menu"), message(block, "submit"), message(block, "cancel"),
                message(block, "reviewDraft"), message(block, "quizMake"), message(block, "rights"));
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
            assertThat(count).as("the sections of the " + help.getKey() + " help").isEqualTo(8);
            if (sections < 0) {
                sections = count;
            }
            assertThat(count).isEqualTo(sections);
        }
    }

    @Test
    void theKnownLimitsOfRemovingContactDetailsAreSaid() throws IOException {
        for (Map.Entry<String, String> help : helpByLanguage().entrySet()) {
            assertThat(help.getValue()).as(help.getKey()).contains("(415) 555-2671").contains("LINE ID");
        }
    }
}
