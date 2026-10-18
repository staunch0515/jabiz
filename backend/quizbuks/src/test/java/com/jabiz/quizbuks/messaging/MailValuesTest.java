package com.jabiz.quizbuks.messaging;

import com.jabiz.mail.MailCategory;
import com.jabiz.mail.MailTemplate;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** How the mail shows its values, and which templates a recipient may turn off where (Q7a, Q7-12, D49). */
class MailValuesTest {

    @Test
    void amountsAreKudosGroupedByThousands() {
        assertThat(MailValues.kudos(new BigDecimal("1234"))).isEqualTo("1,234 Kudos");
        assertThat(MailValues.kudos("1500000")).isEqualTo("1,500,000 Kudos");
        assertThat(MailValues.kudos(BigDecimal.ZERO)).isEqualTo("0 Kudos");
        assertThat(MailValues.kudos(null)).isEqualTo("0 Kudos");
        assertThat(MailValues.count(new BigDecimal("12345"))).isEqualTo("12,345");
    }

    @Test
    void timesAreInUtcWithTheZoneWrittenOut() {
        assertThat(MailValues.time(Instant.parse("2026-10-20T00:30:00Z"))).isEqualTo("2026-10-20 00:30 UTC");
        assertThat(MailValues.time("2026-10-20T09:05:00Z")).isEqualTo("2026-10-20 09:05 UTC");
        assertThat(MailValues.time(null)).isEmpty();
        assertThat(MailValues.time("not a time")).isEmpty();
    }

    @Test
    void onlyTheSponsorsTwoNotificationsMayBeTurnedOffAndOnlyAtTheSponsorConsole() {
        assertThat(NotificationMails.ALL).hasSize(9);
        assertThat(NotificationMails.ALL).filteredOn(MailTemplate::unsubscribable).extracting(MailTemplate::name)
            .containsExactly("qb.publication.funds-short", "qb.publication.ended");
        assertThat(NotificationMails.ALL).filteredOn(MailTemplate::unsubscribable).allSatisfy(template -> {
            assertThat(template.appliesTo("sponsor")).isTrue();
            assertThat(template.appliesTo("app")).isFalse();
            assertThat(template.appliesTo(null)).isFalse();
        });
        // The transactional ones are always sent and name no entry (entries mean nothing for them, D49 item 5).
        assertThat(NotificationMails.ALL).filteredOn(template -> template.category() == MailCategory.TRANSACTIONAL)
            .hasSize(7).allSatisfy(template -> assertThat(template.entries()).isEmpty());
    }
}
