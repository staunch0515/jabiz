package com.jabiz.quizbuks.fee;

import com.jabiz.quizbuks.setup.FirstLaunchSwitches;
import com.jabiz.quizbuks.setup.QbParams;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The help on fees and the Platform fee account (F-F10b, docs/quizbuks/help/fee-account.md) says what the platform
 * does at the first launch: the two amounts of the granted credit and its validity are the parameters' declared values,
 * the three languages ask and answer the same questions in the same order, and what it says about Kudos (shown, not
 * used) is what the registry of the first launch's switches sets. A change that makes the help wrong fails here, not
 * for a Sponsor.
 */
class FeeAccountHelpTest {

    private static final Path HELP = Path.of("../../docs/quizbuks/help/fee-account.md");
    private static final List<String> HEADINGS = List.of("### 中文 · ", "### 日本語 · ", "### English · ");

    private static String text() throws IOException {
        return Files.readString(HELP, StandardCharsets.UTF_8);
    }

    private static Object declared(String key) {
        return QbParams.ALL.stream().filter(param -> param.key().equals(key)).findFirst().orElseThrow().value();
    }

    /** The questions of each language, in order (their headings). */
    private static Map<String, List<String>> questions() throws IOException {
        Map<String, List<String>> found = new java.util.LinkedHashMap<>();
        for (String heading : HEADINGS) {
            List<String> titles = new ArrayList<>();
            Matcher matcher = Pattern.compile("^" + Pattern.quote(heading) + "(.+)$", Pattern.MULTILINE).matcher(text());
            while (matcher.find()) {
                titles.add(matcher.group(1));
            }
            found.put(heading, titles);
        }
        return found;
    }

    @Test
    void theThreeLanguagesAskTheSameQuestionsInTheSameOrder() throws IOException {
        Map<String, List<String>> questions = questions();
        assertThat(questions.get(HEADINGS.getFirst())).hasSizeGreaterThanOrEqualTo(5);
        questions.forEach((heading, titles) -> assertThat(titles.size()).as(heading)
            .isEqualTo(questions.get(HEADINGS.getFirst()).size()));
        // And the sections are for the two audiences, in the same places.
        assertThat(text()).contains("`audience` = `SPONSOR`", "`audience` = `TAKER`");
    }

    @Test
    void theGrantedCreditsAmountsAndValidityAreTheParametersDeclaredValues() throws IOException {
        NumberFormat grouped = NumberFormat.getIntegerInstance(Locale.US);
        String basic = grouped.format(Long.parseLong(String.valueOf(declared(QbParams.FEE_CREDIT_GRANT_BASIC))));
        String certified = grouped.format(Long.parseLong(String.valueOf(declared(QbParams.FEE_CREDIT_GRANT_CERTIFIED))));
        String days = String.valueOf(declared(QbParams.FEE_CREDIT_VALID_DAYS));
        String help = text();
        // The paragraph on what the account is made of, in each language, carries the three numbers.
        for (String marker : List.of("开户的赠送额度", "開設時の付与クレジット", "The onboarding Granted credit")) {
            int start = help.indexOf(marker);
            assertThat(start).as(marker).isGreaterThanOrEqualTo(0);
            String paragraph = help.substring(start, help.indexOf('\n', start));
            assertThat(paragraph).as(marker).containsPattern("(?<![\\d,])" + basic + "(?![\\d,])")
                .containsPattern("(?<![\\d,])" + certified + "(?![\\d,])")
                .containsPattern("(?<![\\d,])" + days + "(?![\\d,])");
        }
    }

    @Test
    void whatItSaysAboutKudosIsTheFirstLaunchsRegistry() throws IOException {
        // "Shown, not used": every switch that would let Kudos be used or reach a Taker is off at the first launch.
        for (String key : List.of(QbParams.KUDOS_BUY_ENABLED, QbParams.KUDOS_SELL_ENABLED, QbParams.TRANSFER_ENABLED,
            QbParams.WITHDRAWAL_BANK_ENABLED, QbParams.REWARD_KUDOS_ENABLED)) {
            assertThat(FirstLaunchSwitches.row(key)).as(key).get().extracting(FirstLaunchSwitches.Row::value)
                .isEqualTo(false);
        }
        // The help names what the Sponsor cannot do now; a statement of the Taker's "coming soon" is the app's label.
        String help = text();
        assertThat(help).contains("学分只显示、不使用", "クドスは表示のみで使用できません", "Kudos are shown but not used");
        // And it promises no date.
        assertThat(help).contains("不承诺", "お約束できません", "cannot promise a date");
        // The monthly fee and the commission it does not quote are zero at the first launch (it names no amount).
        assertThat(FirstLaunchSwitches.row(QbParams.FEE_MONTHLY)).get().extracting(FirstLaunchSwitches.Row::value)
            .isEqualTo("0");
    }
}
