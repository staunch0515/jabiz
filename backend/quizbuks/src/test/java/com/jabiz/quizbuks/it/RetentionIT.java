package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Accounting records are kept ten years from the end of their fiscal year (Q10b, item 13): the journal's exports,
 * parts and confirmations, top-ups, their receipts and Stripe's events appear in the platform's retention report, and
 * an entry falls due on the last day of its fiscal year ten years on (the calendar year: jabiz.fiscal-year-end 12).
 */
class RetentionIT extends PayoutItSupport {

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> policies() {
        Map<String, Object> report = client.get().uri("/api/retention").header(HttpHeaders.AUTHORIZATION,
            TestTokens.bearer(tokens, "retention-reader", "retention.read")).exchange().expectStatus().isOk()
            .expectBody(MAP).returnResult().getResponseBody();
        return (List<Map<String, Object>>) report.get("policies");
    }

    private Map<String, Object> policy(String entity) {
        return policies().stream().filter(p -> entity.equals(p.get("entity"))).findFirst().orElseThrow();
    }

    @Test
    void accountingRecordsAreKeptTenYearsFromTheEndOfTheirFiscalYear() {
        assertThat(policies()).extracting(p -> p.get("entity") + " " + p.get("from") + " " + p.get("keep") + " "
            + p.get("fromFiscalYearEnd")).contains(
            "QbJournalExport periodTo P10Y true",
            "QbJournalExportPart createdTime P10Y true",
            "QbJournalExportConfirmation createdTime P10Y true",
            "QbTopUp paidAt P10Y true",
            "QbTopUpReceipt issuedAt P10Y true",
            "InboundEvent receivedTime P10Y true");

        String sponsor = newSponsor("s-retention");
        String topUpId = openTopUp(sponsor, 5_000);
        deliver(event(eventId(), "checkout.session.completed", completed(topUpId)));
        param(QbParams.ISSUER_NAME, "QuizBuks Operations K.K.");
        run("QB_TOPUP_RECEIPT_ISSUE", sponsorToken(sponsor), body("topUpId", topUpId, "language", "en"));
        Instant paid = instant(topUp(topUpId).get("paid_at"));
        Map<String, Object> receipt = query("SELECT issued_at FROM qb_topup_receipt_version WHERE topup_id = ?",
            topUpId).getFirst();
        assertThat(instant(receipt.get("issued_at"))).isBetween(now().minusSeconds(1), now());

        // Due on the last day of its fiscal year ten years on, not a day before.
        LocalDate paidOn = LocalDate.ofInstant(paid, ZoneOffset.UTC);
        LocalDate due = LocalDate.of(paidOn.getYear() + 10, 12, 31);
        Instant before = now();
        try {
            clock.set(due.minusDays(1).atTime(12, 0).toInstant(ZoneOffset.UTC));
            Map<String, Object> kept = policy("QbTopUp");
            assertThat(kept.get("expiredThrough")).isEqualTo(LocalDate.of(paidOn.getYear() - 1, 12, 31).toString());
            assertThat(((Number) kept.get("expired")).longValue()).isZero();
            assertThat(((Number) policy("QbTopUpReceipt").get("expired")).longValue()).isZero();

            clock.set(due.atTime(0, 0).toInstant(ZoneOffset.UTC));
            Map<String, Object> past = policy("QbTopUp");
            assertThat(past.get("expiredThrough")).isEqualTo(LocalDate.of(paidOn.getYear(), 12, 31).toString());
            assertThat(((Number) past.get("expired")).longValue()).isPositive();
            assertThat(((Number) policy("QbTopUpReceipt").get("expired")).longValue()).isPositive();
            assertThat(((Number) policy("InboundEvent").get("expired")).longValue()).isPositive();
        } finally {
            clock.set(before);
        }
    }
}
