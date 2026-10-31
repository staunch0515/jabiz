package com.jabiz.quizbuks.messaging;

import com.jabiz.mail.MailCategory;
import com.jabiz.mail.MailTemplate;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** How the mail shows its values, and which templates a recipient may turn off where (Q7a, Q7-12, D49). */
class MailValuesTest {

    @Test
    void amountsAreNumbersGroupedByThousands() {
        assertThat(MailValues.amount(new BigDecimal("1234"))).isEqualTo("1,234");
        assertThat(MailValues.amount("1500000")).isEqualTo("1,500,000");
        assertThat(MailValues.amount(BigDecimal.ZERO)).isEqualTo("0");
        assertThat(MailValues.amount(null)).isEqualTo("0");
        assertThat(MailValues.count(new BigDecimal("12345"))).isEqualTo("12,345");
    }

    /** The mail parameters are numbers: each language's template writes the points' name after them (Q61). */
    @Test
    void theMailTemplatesNameThePointsAfterEveryAmountInTheirLanguage() throws IOException {
        Map<String, String> units = Map.of("en", "Kudos", "zh", "学分", "ja", "クドス");
        Pattern amount = Pattern.compile("\\{(amount|fee|net|pendingAmount|maxReward)}(?! ?(Kudos|学分|クドス))");
        for (Map.Entry<String, String> unit : units.entrySet()) {
            Properties messages = new Properties();
            try (InputStream in = MailValuesTest.class.getResourceAsStream("/messages_" + unit.getKey() + ".properties")) {
                messages.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
            assertThat(messages.getProperty("qb.kudos.unit")).isEqualTo(unit.getValue());
            for (String template : List.of("qb.transfer.sent", "qb.transfer.failed", "qb.topup.paid", "qb.topup.granted",
                "qb.publication.funds-short", "qb.invitation")) {
                for (String part : List.of("subject", "body")) {
                    String text = messages.getProperty("mail." + template + "." + part);
                    assertThat(text).as("mail.%s.%s (%s)", template, part, unit.getKey()).isNotNull()
                        .doesNotContain("Kudos".equals(unit.getValue()) ? "学分" : "Kudos");
                    assertThat(amount.matcher(text).find()).as("an amount without its unit in mail.%s.%s (%s)",
                        template, part, unit.getKey()).isFalse();
                }
            }
        }
    }

    @Test
    void timesAreInTheGivenZoneLabelledByIt() {
        MailValues japan = new MailValues(ZoneId.of("Asia/Tokyo"));
        assertThat(japan.time(Instant.parse("2026-10-20T00:30:00Z"))).isEqualTo("2026-10-20 09:30 JST");
        // Across the date line of UTC: Japan's date.
        assertThat(japan.time("2026-12-31T15:05:00Z")).isEqualTo("2027-01-01 00:05 JST");
        assertThat(japan.time(null)).isEmpty();
        assertThat(japan.time("not a time")).isEmpty();
        // The label is the zone's, not written in the code.
        assertThat(new MailValues(ZoneId.of("UTC")).time("2026-10-20T09:05:00Z")).isEqualTo("2026-10-20 09:05 UTC");
    }

    @Test
    void namesAreQuotedAndCutAtTheLimit() {
        assertThat(MailValues.names(List.of("Quiz A"), 10)).isEqualTo("\"Quiz A\"");
        assertThat(MailValues.names(List.of("A", "B", "C"), 2)).isEqualTo("\"A\", \"B\", …");
        assertThat(MailValues.names(List.of(), 10)).isEmpty();
    }

    @Test
    void onlyTheSponsorsTwoNotificationsMayBeTurnedOffAndOnlyAtTheSponsorConsole() {
        assertThat(NotificationMails.ALL).hasSize(10);
        assertThat(NotificationMails.ALL).filteredOn(MailTemplate::unsubscribable).extracting(MailTemplate::name)
            .containsExactly("qb.publication.funds-short", "qb.publication.ended");
        assertThat(NotificationMails.ALL).filteredOn(MailTemplate::unsubscribable).allSatisfy(template -> {
            assertThat(template.appliesTo("sponsor")).isTrue();
            assertThat(template.appliesTo("app")).isFalse();
            assertThat(template.appliesTo(null)).isFalse();
        });
        // The transactional ones are always sent and name no entry (entries mean nothing for them, D49 item 5).
        assertThat(NotificationMails.ALL).filteredOn(template -> template.category() == MailCategory.TRANSACTIONAL)
            .hasSize(8).allSatisfy(template -> assertThat(template.entries()).isEmpty());
    }
}
