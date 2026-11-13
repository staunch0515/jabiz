package com.jabiz.quizbuks.fee;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M6 (N1 of Fable's second review): the places that decide which months a publication's distribution fee is due
 * for (the monthly job's items, the postpaid invoice's wait, the daily check and, from F-M12 b, the operator settlement's wait) start from when it went on: the
 * greater of its start and its approval, written the same in each, and none of them reads the start alone.
 */
class DistributionDueConditionTest {

    static final Path SYSTEM = Path.of("src/main/resources/queries/qb/system");
    static final List<String> TEMPLATES = List.of("distribution-due.sql", "fee-invoices-due.sql",
        "recon-prepayments.sql", "operator-settlement-readiness.sql");
    static final Pattern WENT_ON = Pattern.compile(
        "greatest\\((\\w+)\\.\\{\\{QbPublicationCard\\.startAt}}, (\\w+)\\.\\{\\{QbPublication\\.approvedAt}}\\)");

    @Test
    void theFourStartFromTheGreaterOfTheStartAndTheApproval() throws IOException {
        for (String template : TEMPLATES) {
            String sql = Files.readString(SYSTEM.resolve(template));
            Matcher matcher = WENT_ON.matcher(sql);
            assertThat(matcher.find()).as(template).isTrue();
            assertThat(matcher.find()).as(template + ": once").isFalse();
            // The start is read only inside that expression.
            assertThat(sql.split("\\{\\{QbPublicationCard\\.startAt}}", -1)).as(template).hasSize(2);
        }
    }
}
