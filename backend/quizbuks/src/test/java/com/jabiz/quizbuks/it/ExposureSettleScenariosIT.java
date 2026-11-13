package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.fee.ExposureSettlements;
import com.jabiz.quizbuks.fee.FeeProcesses;
import com.jabiz.quizbuks.fee.FeeRequests;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M8c (review M4; plan §7): the settlements of the exposure fee where they meet one another and the accounts: the
 * monthly and the final one in both orders and at once, the same item twice at once, a postpaid sponsor and the
 * invoice that waits for the month, a deposit that is not enough, a tax rate that changes between two settlements,
 * and a charge voided.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ExposureSettleScenariosIT extends ExposureFeeItSupport {

    static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");

    /** A live publication of a new Sponsor using another's video, {@code purchased} exposures bought, one yen each. */
    String[] buy(String name, long purchased, Long creditYen, boolean postpaid) {
        autoApprove(true);
        String provider = reviewedSponsor(name + "-provider");
        String sharedVideoId = approvedVideo(provider);
        String sponsor = reviewedSponsor(name);
        if (postpaid) {
            billing(sponsor, "POSTPAID", 100_000);
        } else {
            grant(sponsor, creditYen, clock.instant().plus(Duration.ofDays(400)));
        }
        String publicationId = draft(sponsor, name, sharedVideoId, purchased, clock.instant());
        submit(sponsorToken(sponsor), publicationId);
        return new String[] {sponsor, publicationId, provider};
    }

    void successes(String publicationId, String sponsor, String provider, int n) {
        for (int i = 0; i < n; i++) {
            execute("INSERT INTO qb_exposure (exposure_id, publication_id, user_id, publisher_id, provider_id, "
                + "counted_at, surface, counted_sec, timer, capped_days, created_at, version) VALUES (?, ?, ?, ?, ?, "
                + "?, 'START', 10, 'SCREEN', 0, ?, 1)", UUID.randomUUID().toString(), publicationId,
                "fm8-" + UUID.randomUUID(), sponsor, provider, Timestamp.from(clock.instant()),
                Timestamp.from(clock.instant()));
        }
    }

    static String period(Instant at) {
        return com.jabiz.quizbuks.fee.FeeRules.period(at, ZONE);
    }

    /** The 2nd of the month after {@code month} at 00:47 (the monthly run's time). */
    static Instant runTimeAfter(String month) {
        return YearMonth.parse(month).plusMonths(1).atDay(2).atTime(0, 47).atZone(ZONE).toInstant();
    }

    static Map<String, Object> ref(String publicationId, String sponsor, String period, boolean last) {
        return period == null ? Map.of("publicationId", publicationId, "sponsorId", sponsor, "last", last)
            : Map.of("publicationId", publicationId, "sponsorId", sponsor, "period", period, "last", last);
    }

    Object settle(String publicationId, String sponsor, String period, boolean last) {
        return ((FeeProcesses.ItemOutput) systemRun(ExposureSettlements.SETTLE, ref(publicationId, sponsor, period,
            last))).outcome();
    }

    static List<Map<String, Object>> settlements(String publicationId) {
        return query("SELECT * FROM qb_exposure_settlement_version WHERE publication_id = ? ORDER BY period, row_id",
            publicationId);
    }

    static List<Map<String, Object>> charges(String sponsor) {
        return query("SELECT * FROM qb_fee_charge_version WHERE sponsor_id = ? AND kind = 'EXPOSURE' "
            + "ORDER BY charged_at, charge_id", sponsor);
    }

    static BigDecimal dec(Object value) {
        return new BigDecimal(String.valueOf(value));
    }

    static BigDecimal sum(List<Map<String, Object>> rows, String column) {
        return rows.stream().map(row -> dec(row.get(column))).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    List<Map<String, Object>> invoicesDue(String current) {
        return items(FeeProcesses.INVOICES_DUE_TEMPLATE, TestTokens.bearer(tokens, "fm8-system",
            QbPermissions.FEE_SYSTEM), Map.of("params", Map.of("current", current, "zone", ZONE.getId())));
    }

    static List<Object> atOnce(Supplier<Object> one, Supplier<Object> two) {
        java.util.concurrent.CompletableFuture<Object> a = java.util.concurrent.CompletableFuture.supplyAsync(one);
        java.util.concurrent.CompletableFuture<Object> b = java.util.concurrent.CompletableFuture.supplyAsync(two);
        return java.util.stream.Stream.of(a, b).map(f -> {
            try {
                return f.get();
            } catch (Exception e) {
                return (Object) ("failed: " + e.getMessage());
            }
        }).toList();
    }

    String contentWriter() {
        return TestTokens.bearer(tokens, "fm8-scenarios-admin", QbPermissions.ADMIN_CONTENT_WRITE);
    }

    void end(String publicationId) {
        run("QB_PUBLICATION_ADMIN_END", contentWriter(), body("publicationId", publicationId, "reason", "F-M8 test"));
    }

    @Test
    void theMonthlyAndTheFinalSettlementInBothOrdersAndAtOnceWriteOneFinalRowAndGiveBackOnce() {
        String month = period(clock.instant());
        String[][] pubs = {buy("fm8-ov-a", 100, 1_000L, false), buy("fm8-ov-b", 100, 1_000L, false),
            buy("fm8-ov-c", 100, 1_000L, false)};
        for (String[] p : pubs) {
            successes(p[1], p[0], p[2], 30);
            end(p[1]);
        }
        // Both see it ended past the grace.
        clock.set(runTimeAfter(month));
        settle(pubs[0][1], pubs[0][0], month, false);
        settle(pubs[0][1], pubs[0][0], null, true);
        settle(pubs[1][1], pubs[1][0], null, true);
        settle(pubs[1][1], pubs[1][0], month, false);
        atOnce(() -> settle(pubs[2][1], pubs[2][0], month, false), () -> settle(pubs[2][1], pubs[2][0], null, true));
        for (String[] p : pubs) {
            assertThat(settlements(p[1])).as(p[1]).singleElement().satisfies(row -> {
                assertThat(row).containsEntry("final", true);
                assertThat(dec(row.get("amount"))).isEqualByComparingTo("30");
            });
            assertThat(charges(p[0])).hasSize(1);
            assertThat(exposurePrepayment(p[1])).containsEntry("status", "RELEASED");
            assertThat(ledger("2180", p[0])).isEqualByComparingTo("0");
            assertThat(differences(p[0])).isEmpty();
        }
    }

    @Test
    void theSameItemRunTwiceAtOnceWritesOneRowAndChargesOnce() {
        String[] p = buy("fm8-twice", 100, 1_000L, false);
        successes(p[1], p[0], p[2], 20);
        String month = period(clock.instant());
        clock.set(runTimeAfter(month));
        List<Object> outcomes = atOnce(() -> settle(p[1], p[0], month, false), () -> settle(p[1], p[0], month, false));
        assertThat(outcomes).as("at least one ran").anyMatch(o -> !String.valueOf(o).startsWith("failed"));
        assertThat(settlements(p[1])).singleElement()
            .satisfies(row -> assertThat(dec(row.get("amount"))).isEqualByComparingTo("20"));
        assertThat(charges(p[0])).hasSize(1);
        assertThat(differences(p[0])).isEmpty();
    }

    @Test
    void aPostpaidSponsorOwesTheFeeTheInvoiceWaitsForTheMonthAndAVoidGivesItBack() {
        String[] a = buy("fm8-pp-a", 100, null, true);
        // A second publication of the same sponsor.
        String sharedVideoId = approvedVideo(reviewedSponsor("fm8-pp-b-provider"));
        String second = draft(a[0], "fm8-pp-b", sharedVideoId, 100L, clock.instant());
        submit(sponsorToken(a[0]), second);
        assertThat(exposurePrepayment(a[1])).containsEntry("mode", "POSTPAID");
        String month = period(clock.instant());
        successes(a[1], a[0], a[2], 10);
        successes(second, a[0], "fm8-pp-b-provider", 5);
        YearMonth next = YearMonth.parse(month).plusMonths(1);
        clock.set(runTimeAfter(month));

        // A settled, B not: the month's invoice waits.
        settle(a[1], a[0], month, false);
        assertThat(invoicesDue(next.toString()).stream().filter(row -> a[0].equals(row.get("sponsorId")))).isEmpty();
        // A's 10 and 1 owed (1130), not paid; its prepayment stops counting by as much.
        assertThat(ledger("1130", a[0])).isEqualByComparingTo("-11");
        assertThat(dec(exposurePrepayment(a[1]).get("remaining"))).isEqualByComparingTo("99");
        // Both settled: due.
        settle(second, a[0], month, false);
        assertThat(invoicesDue(next.toString())).filteredOn(row -> a[0].equals(row.get("sponsorId")))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("period", month));
        assertThat(((FeeProcesses.ItemOutput) systemRun(FeeProcesses.INVOICE_ITEM, Map.of("sponsorId", a[0],
            "period", month))).outcome()).isEqualTo("ISSUED");
        // The invoice is voided (finance asks, another administrator approves): the postings are reversed.
        Map<String, Object> invoice = query("SELECT * FROM qb_fee_invoice WHERE sponsor_id = ? ORDER BY issued_at",
            a[0]).getFirst();
        String finance = tokenOf(user("fm8-sc-finance"), QbRoles.ADMIN_FINANCE);
        Map<String, Object> voiding = run(FeeRequests.RECORD, finance, body("kind", "VOID", "invoiceId",
            String.valueOf(invoice.get("invoice_id")), "reason", "Charged twice"));
        run("APPROVAL_DECIDE", tokenOf(user("fm8-sc-content"), QbRoles.ADMIN_CONTENT),
            body("requestId", voiding.get("approvalRequestId"), "decision", "APPROVE"));
        deliverEvents();
        assertThat(query("SELECT status FROM qb_fee_invoice WHERE invoice_id = ?", invoice.get("invoice_id"))
            .getFirst()).containsEntry("status", "VOID");
        assertThat(ledger("1130", a[0])).isEqualByComparingTo("0");
    }

    @Test
    void beyondTheDepositTheAccountPaysAndTheRestIsOwedPartlyThenWhole() {
        // The tax rate rises before the settlement: 100 yen and 20 tax = 120 against a deposit of 110.
        String[] a = buy("fm8-short-a", 100, 110L, false);
        cash(a[0], 5);
        successes(a[1], a[0], a[2], 100);
        String month = period(clock.instant());
        param(QbParams.FEE_TAX_RATE, "0.2");
        try {
            clock.set(runTimeAfter(month));
            settle(a[1], a[0], month, false);
        } finally {
            param(QbParams.FEE_TAX_RATE, "0.1");
        }
        Map<String, Object> charge = charges(a[0]).getFirst();
        assertThat(dec(charge.get("total"))).isEqualByComparingTo("120");
        // The deposit (110) and the cash (5) paid; 5 are owed.
        assertThat(query("SELECT status, total, paid_amount FROM qb_fee_invoice WHERE charge_id = ?",
            String.valueOf(charge.get("charge_id")))).singleElement().satisfies(invoice -> {
                assertThat(invoice).containsEntry("status", "PARTIAL");
                assertThat(dec(invoice.get("paid_amount"))).isEqualByComparingTo("115");
            });
        // Nothing to pay from at all: the invoice is open.
        String[] b = buy("fm8-short-b", 100, 110L, false);
        execute("UPDATE qb_prepayment SET remaining = 0 WHERE publication_id = ? AND purpose = 'EXPOSURE'", b[1]);
        successes(b[1], b[0], b[2], 30);
        settle(b[1], b[0], period(clock.instant()), false);
        assertThat(query("SELECT status, paid_amount FROM qb_fee_invoice WHERE charge_id = ?",
            String.valueOf(charges(b[0]).getFirst().get("charge_id")))).singleElement().satisfies(invoice -> {
                assertThat(invoice).containsEntry("status", "OPEN");
                assertThat(dec(invoice.get("paid_amount"))).isEqualByComparingTo("0");
            });
    }

    @Test
    void aTaxRateThatChangesBetweenTwoSettlementsIsCaughtUpAndNeverGivenBack() {
        String month = period(clock.instant());
        // Raised (10% to 20%): the month's tax on 50 is 5; the final settlement of 100 owes 20 in all (15 more).
        String[] up = buy("fm8-rate-up", 100, 1_000L, false);
        successes(up[1], up[0], up[2], 50);
        clock.set(runTimeAfter(month));
        settle(up[1], up[0], month, false);
        successes(up[1], up[0], up[2], 50);
        end(up[1]);
        // Lowered (20% to 10%): the month's tax on 50 is 10; the final one owes 10 in all, nothing more, none back.
        param(QbParams.FEE_TAX_RATE, "0.2");
        try {
            String[] down = buy("fm8-rate-down", 100, 1_000L, false);
            successes(down[1], down[0], down[2], 50);
            String downMonth = period(clock.instant());
            clock.set(runTimeAfter(downMonth));
            settle(down[1], down[0], downMonth, false);
            successes(down[1], down[0], down[2], 50);
            end(down[1]);
            param(QbParams.FEE_TAX_RATE, "0.1");
            clock.set(clock.instant().plus(Duration.ofHours(3)));
            settle(down[1], down[0], null, true);
            assertThat(sum(settlements(down[1]), "tax")).isEqualByComparingTo("10");
            assertThat(sum(settlements(down[1]), "amount")).isEqualByComparingTo("100");
            assertThat(differences(down[0])).isEmpty();
        } finally {
            param(QbParams.FEE_TAX_RATE, "0.1");
        }
        param(QbParams.FEE_TAX_RATE, "0.2");
        try {
            clock.set(clock.instant().plus(Duration.ofHours(3)));
            settle(up[1], up[0], null, true);
        } finally {
            param(QbParams.FEE_TAX_RATE, "0.1");
        }
        assertThat(sum(settlements(up[1]), "tax")).isEqualByComparingTo("20");
        assertThat(sum(settlements(up[1]), "amount")).isEqualByComparingTo("100");
        assertThat(differences(up[0])).isEmpty();
    }
}
