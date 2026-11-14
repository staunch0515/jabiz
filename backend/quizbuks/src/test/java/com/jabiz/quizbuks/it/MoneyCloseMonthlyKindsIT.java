package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.fee.MoneyClose;
import com.jabiz.quizbuks.fee.Prepayments;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M15 b (re-review L5): the postings of two real wind-ups (after ACCOUNT_ERASE) in the monthly report, row by row,
 * with {@link AdminFinanceIT}'s columns: one paid from cash (FEE_PAID: 2160 / 1130), one whose granted credit was
 * frozen in a prepayment, given back by the release, used for the invoice and then voided (PREPAYMENT_FROZEN,
 * PREPAYMENT_RELEASED, FEE_PAID: 2190 / 1130, FEE_CREDIT_VOIDED). No new kind, nothing in OTHER. (AdminFinanceIT's own
 * case registers a wind-up without the erasure: that class has no maintenance connection.)
 */
class MoneyCloseMonthlyKindsIT extends MoneyCloseItSupport {

    @Test
    void twoWindUpsPostingsAreTheKindsThatExistRowByRow() {
        autoApprove(true);
        ZonedDateTime month = clock.instant().atZone(ZoneOffset.UTC).plusMonths(7).withDayOfMonth(1)
            .truncatedTo(ChronoUnit.DAYS);
        Instant from = month.toInstant();
        Instant to = month.plusMonths(1).toInstant();
        clock.set(from.plus(Duration.ofHours(1)));

        // Paid from cash.
        String fromCash = newSponsor(unique("s-fm15b-kinds-cash"));
        billing(fromCash, "POSTPAID", 50_000);
        cash(fromCash, 2_000);
        manualFee(fromCash, 300);
        // Granted credit frozen in a prepayment, given back, used, the rest voided.
        String fromCredit = pricedSponsor("fm15b-kinds-credit");
        grant(fromCredit, 1_000, to.plus(Duration.ofDays(400)));
        String publicationId = audienceDraft(fromCredit, "FM15b kinds");
        submit(sponsorToken(fromCredit), publicationId);
        billing(fromCredit, "POSTPAID", 50_000);
        manualFee(fromCredit, 300);

        clock.set(from.plus(Duration.ofHours(2)));
        erase(fromCash);
        erase(fromCredit);
        assertThat(close(fromCash).outcome()).isEqualTo(MoneyClose.CLOSED);
        assertThat(close(fromCredit).outcome()).isEqualTo(MoneyClose.WAITING_DEPOSITS);
        if (!"ENDED".equals(status(publicationId))) {
            run("QB_PUBLICATION_ADMIN_END", TestTokens.bearer(tokens, "fm15b-content", QbPermissions.ADMIN_CONTENT_WRITE),
                Map.of("publicationId", publicationId, "reason", "F-M15 b L5"));
        }
        systemRun(Prepayments.RELEASE_ITEM, Map.of("publicationId", publicationId, "sponsorId", fromCredit,
            "purpose", "AUDIENCE"));
        assertThat(close(fromCredit).outcome()).isEqualTo(MoneyClose.CLOSED);

        List<AdminFinanceIT.Expected> expected = List.of(
            AdminFinanceIT.row("FEE_CHARGED", 2, 660, "1130", 660, "4120", -600, "2150", -60),
            AdminFinanceIT.row("FEE_PAID", 2, 660, "2160", 330, "2190", 330, "1130", -660),
            AdminFinanceIT.row("PREPAYMENT_FROZEN", 1, 770, "2190", 770, "2180", -770),
            AdminFinanceIT.row("PREPAYMENT_RELEASED", 1, 770, "2180", 770, "2190", -770),
            AdminFinanceIT.row("FEE_CREDIT_VOIDED", 1, 670, "2190", 670, "5140", -670));
        Map<String, Map<String, Object>> monthly = items("qb.admin.monthly", finance(), Map.of("params",
            Map.of("from", from.toString(), "to", to.toString()), "limit", 100)).stream()
            .collect(Collectors.toMap(r -> (String) r.get("category"), Function.identity()));
        for (AdminFinanceIT.Expected want : expected) {
            Map<String, Object> got = monthly.get(want.category());
            assertThat(((Number) got.get("transactionCount")).longValue()).as(want.category() + " count " + got)
                .isEqualTo(want.count());
            assertThat(money(got.get("amount"))).as(want.category() + " amount " + got)
                .isEqualByComparingTo(BigDecimal.valueOf(want.amount()));
            for (Map.Entry<String, String> column : AdminFinanceIT.COLUMNS.entrySet()) {
                assertThat(money(got.get(column.getValue()))).as(want.category() + " " + column.getKey() + " " + got)
                    .isEqualByComparingTo(BigDecimal.valueOf(want.moves().getOrDefault(column.getKey(), 0L)));
            }
        }
        // OTHER holds only the test's development credit (no source; it funds the cash), none of the wind-ups'.
        assertThat(((Number) monthly.get("OTHER").get("transactionCount")).longValue()).isEqualTo(1);
        assertThat(query("SELECT count(*) AS n FROM ledger_transaction_version WHERE booking_time >= ? AND booking_time < ? "
            + "AND source_entity IS NULL AND description <> 'Development credit'", java.sql.Timestamp.from(from),
            java.sql.Timestamp.from(to)).getFirst().get("n")).isEqualTo(0L);
        assertBooksBalance();
        assertThat(differences(fromCash)).isEmpty();
        assertThat(differences(fromCredit)).isEmpty();
    }
}
