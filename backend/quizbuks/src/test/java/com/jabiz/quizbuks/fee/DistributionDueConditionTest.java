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
 * F-M6 (N1 of Fable's second review), F-M8: the places that decide which months a publication's distribution or exposure
 * fee is due for (the monthly jobs' items, the postpaid invoice's wait, the daily checks and the operator settlement's
 * readiness) start from when it went on: the greater of its start and its approval, written the same in each, and none
 * of them reads the start alone.
 */
class DistributionDueConditionTest {

    static final Path SYSTEM = Path.of("src/main/resources/queries/qb/system");
    /**
     * The templates and how many due conditions (the distribution fee's or the exposure fee's) each holds: the two fees'
     * own due templates one each, the postpaid invoice's wait and the operator settlement's readiness both fees' (two), the
     * daily check one in each of its two statements (prepayments: distribution; exposure: exposure).
     */
    static final java.util.Map<String, Integer> TEMPLATES = java.util.Map.of("distribution-due.sql", 1,
        "exposure-due.sql", 1, "fee-invoices-due.sql", 2, "recon-prepayments.sql", 1,
        "recon-exposure.sql", 1, "operator-settlement-readiness.sql", 2,
        // F-M12 c: the daily check of the waiting settlements: the readiness branches (2) and the times they were ready (2).
        "recon-operator-settlement.sql", 4);
    static final Pattern WENT_ON = Pattern.compile(
        "greatest\\((\\w+)\\.\\{\\{QbPublicationCard\\.startAt}}, (\\w+)\\.\\{\\{QbPublication\\.approvedAt}}\\)");

    @Test
    void theTemplatesStartFromTheGreaterOfTheStartAndTheApproval() throws IOException {
        for (var entry : TEMPLATES.entrySet()) {
            String template = entry.getKey();
            String sql = Files.readString(SYSTEM.resolve(template));
            Matcher matcher = WENT_ON.matcher(sql);
            int found = 0;
            while (matcher.find()) {
                found++;
            }
            assertThat(found).as(template).isEqualTo(entry.getValue());
            // The start is read only inside that expression.
            assertThat(sql.split("\\{\\{QbPublicationCard\\.startAt}}", -1)).as(template)
                .hasSize(entry.getValue() + 1);
        }
    }
}
