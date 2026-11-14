package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.fee.AudienceRefundJob;
import com.jabiz.quizbuks.fee.FeeProcesses;
import com.jabiz.quizbuks.journal.JournalLines;
import com.jabiz.quizbuks.journal.JournalPeriods;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.wallet.Reconciliation;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review L4 of PR 683: the journal export through its real path ({@code QB_JOURNAL_EXPORT}, which needs a clean
 * reconciliation) for account 1130 (fees receivable) and 2180 (prepayments set aside), both kept per Sponsor on the
 * voucher ({@link JournalLines#PER_SPONSOR}). {@link JournalFeeAccountsIT} covers them with postings by hand and the
 * preview; hand postings make the reconciliation dirty, so here the postings are the fee processes' own (monthly fees
 * charged and paid, an audience fee set aside, paid from and given back), which keep it clean.
 *
 * <p>Month one holds three Sponsors' fees (two paid from cash, one postpaid and owed) and two Sponsors' audience
 * prepayments; month two the next monthly fees of the two paying Sponsors. Both months are exported in order and the
 * kept files are read back: a voucher line per account, side and Sponsor, every detail row on a line, and every line
 * the sum of its rows.
 */
class JournalFeeExportIT extends CreditNoteItSupport {

    @Test
    void feesReceivableAndPrepaymentsAreExportedPerSponsorAndTheDetailAddsUpToEachLine() throws Exception {
        param(QbParams.FEE_MONTHLY, "1000");
        param(QbParams.AUDIENCE_REFUND_UNUSED_DAYS, "true");
        autoApprove(true);
        feeRate("0.2");
        YearMonth first = YearMonth.from(clock.instant().atZone(JournalPeriods.ZONE).plusMonths(1).withDayOfMonth(1)
            .truncatedTo(ChronoUnit.DAYS));
        JournalPeriods.Period one = JournalPeriods.month(first);
        JournalPeriods.Period two = JournalPeriods.month(first.plusMonths(1));
        clock.set(one.start().plus(Duration.ofDays(2)));

        // 1130: two Sponsors pay the monthly fee from their cash (charged, then paid); a third owes it (postpaid).
        String payerA = newSponsor(unique("s-jfe-a"));
        cash(payerA, 5_000);
        String payerB = newSponsor(unique("s-jfe-b"));
        cash(payerB, 5_000);
        String owing = newSponsor(unique("s-jfe-c"));
        billing(owing, "POSTPAID", 50_000);
        for (String sponsor : List.of(payerA, payerB, owing)) {
            assertThat(monthly(sponsor)).isEqualTo("CHARGED");
        }

        // 2180: two Sponsors set an audience fee aside, pay from it (a taker takes the one place) and get the rest back.
        String audienceA = audienceSponsorWithPrepaymentUsedAndReleased("jfe-audience-a");
        String audienceB = audienceSponsorWithPrepaymentUsedAndReleased("jfe-audience-b");
        deliverEvents();

        // Month one: reconciled after its end, every account mapped, then exported.
        Instant reconciled = one.end().plus(Duration.ofHours(10));
        clock.set(one.end().plus(Duration.ofHours(1)));
        mapAll();
        clock.set(reconciled);
        assertThat(reconcile(reconciled)).isEqualTo("OK");
        advance(JournalPeriods.SETTLE.plusMinutes(1));
        Map<String, Object> exportOne = export(first.toString());

        Set<String> paying = Set.of(payerA, payerB);
        Set<String> audience = Set.of(audienceA, audienceB);
        // The audience fee is charged to the Sponsor's receivable and paid from the prepayment set aside for it.
        Set<String> owed = new HashSet<>(Set.of(payerA, payerB, owing, audienceA, audienceB));
        Set<String> settled = new HashSet<>(paying);
        settled.addAll(audience);
        Voucher voucherOne = read(exportOne);
        assertThat(sponsorsOf(voucherOne, "1130", "DEBIT")).isEqualTo(owed);
        assertThat(sponsorsOf(voucherOne, "1130", "CREDIT")).isEqualTo(settled);
        // 2180: the amount set aside on the credit side, what was used and given back on the debit side.
        assertThat(sponsorsOf(voucherOne, "2180", "CREDIT")).isEqualTo(audience);
        assertThat(sponsorsOf(voucherOne, "2180", "DEBIT")).isEqualTo(audience);
        // Each Sponsor's line agrees with the ledger's own balance of the account for them (credit less debit).
        for (String sponsor : owed) {
            assertThat(net(voucherOne, "1130", sponsor).negate()).as("1130 " + sponsor)
                .isEqualByComparingTo(ledger("1130", sponsor));
        }
        for (String sponsor : audience) {
            assertThat(net(voucherOne, "2180", sponsor).negate()).as("2180 " + sponsor)
                .isEqualByComparingTo(ledger("2180", sponsor));
        }

        // Month two: the paying Sponsors' next monthly fee; reconciled, exported in order after month one.
        clock.set(two.start().plus(Duration.ofDays(2)));
        for (String sponsor : List.of(payerA, payerB)) {
            assertThat(monthly(sponsor)).isEqualTo("CHARGED");
        }
        deliverEvents();
        reconciled = two.end().plus(Duration.ofHours(10));
        clock.set(reconciled);
        assertThat(reconcile(reconciled)).isEqualTo("OK");
        advance(JournalPeriods.SETTLE.plusMinutes(1));
        Map<String, Object> exportTwo = export(first.plusMonths(1).toString());
        assertThat(exportTwo.get("voucherNo")).isNotEqualTo(exportOne.get("voucherNo"));
        Voucher voucherTwo = read(exportTwo);
        assertThat(sponsorsOf(voucherTwo, "1130", "DEBIT")).isEqualTo(paying);
        assertThat(sponsorsOf(voucherTwo, "1130", "CREDIT")).isEqualTo(paying);
        assertThat(sponsorsOf(voucherTwo, "2180", "DEBIT")).isEmpty();
        assertThat(sponsorsOf(voucherTwo, "2180", "CREDIT")).isEmpty();
        // #688 review L10: the amounts too. Each paying Sponsor's month-two fee with its tax, charged (debit) and paid
        // (credit) within the month: both lines are that fee.
        for (String sponsor : paying) {
            List<Map<String, Object>> fees = query("SELECT amount + tax AS total FROM qb_fee_charge_version "
                + "WHERE sponsor_id = ? AND kind = 'MONTHLY' AND period = ?", sponsor, first.plusMonths(1).toString());
            assertThat(fees).as("month two's fee of " + sponsor).hasSize(1);
            BigDecimal total = new BigDecimal(String.valueOf(fees.getFirst().get("total")));
            assertThat(total).isPositive();
            assertThat(side(voucherTwo, "1130", "DEBIT", sponsor)).as("1130 debit " + sponsor)
                .isEqualByComparingTo(total);
            assertThat(side(voucherTwo, "1130", "CREDIT", sponsor)).as("1130 credit " + sponsor)
                .isEqualByComparingTo(total);
        }
    }

    /** A Sponsor's lines on one side of an account on the voucher, added up. */
    private static BigDecimal side(Voucher voucher, String account, String side, String sponsor) {
        return voucher.lines().stream().filter(l -> account.equals(l.get("platform_account"))
                && side.equals(l.get("side")) && sponsor.equals(l.get("sponsor_id")))
            .map(l -> new BigDecimal(l.get("amount"))).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** A Sponsor whose audience fee is set aside, paid from by a taker's place and the rest given back. */
    private String audienceSponsorWithPrepaymentUsedAndReleased(String name) {
        String sponsor = pricedSponsor(name);
        cash(sponsor, 1_000);
        credit(sponsor, 10_000);
        String publicationId = oneWinnerAudienceDraft(sponsor, name);
        submit(sponsorToken(sponsor), publicationId);
        goLive(publicationId);
        advance(Duration.ofDays(2));
        play(womanTaker("t-" + name), publicationId, true, true);
        Map<String, Object> ref = body("prepaymentId", String.valueOf(prepayments(publicationId).getFirst()
            .get("prepayment_id")), "publicationId", publicationId, "sponsorId", sponsor, "endedAt",
            clock.instant().toString());
        assertThat(((FeeProcesses.ItemOutput) systemRun(AudienceRefundJob.ITEM, ref)).outcome()).isEqualTo("REFUNDED");
        return sponsor;
    }

    // ---- the kept files ----

    /** A voucher read back: its lines (the voucher file) and detail rows (every part), by column name. */
    private record Voucher(String voucherNo, List<Map<String, String>> lines, List<Map<String, String>> detail) {}

    private Voucher read(Map<String, Object> export) throws Exception {
        List<List<String>> voucherRows = csv(download((String) export.get("fileId"), finance()));
        assertThat(voucherRows.getFirst()).containsExactlyElementsOf(JournalLines.VOUCHER_COLUMNS);
        List<Map<String, String>> lines = named(JournalLines.VOUCHER_COLUMNS, voucherRows);

        List<List<String>> detailRows = new ArrayList<>();
        detailRows.add(JournalLines.DETAIL_COLUMNS);
        int partNo = 1;
        for (Map<String, Object> part : detailFiles(export)) {
            assertThat(part).containsEntry("partNo", partNo++);
            byte[] bytes = download((String) part.get("fileId"), finance());
            assertThat(sha256(bytes)).isEqualTo(part.get("sha256"));
            List<List<String>> rows = csv(bytes);
            assertThat(rows.getFirst()).containsExactlyElementsOf(JournalLines.DETAIL_COLUMNS);
            detailRows.addAll(rows.subList(1, rows.size()));
        }
        List<Map<String, String>> detail = named(JournalLines.DETAIL_COLUMNS, detailRows);
        assertThat(detail).isNotEmpty();

        // The voucher: one line per account, side and Sponsor (no key twice), numbered 1..n, balanced.
        Set<String> keys = new HashSet<>();
        for (Map<String, String> line : lines) {
            assertThat(keys.add(JournalLines.key(line.get("platform_account"), line.get("side"),
                line.get("sponsor_id")))).as("one line per account, side and sponsor: " + line).isTrue();
            if (Set.of("1130", "2180").contains(line.get("platform_account"))) {
                assertThat(line.get("sponsor_id")).as("sponsor on " + line).isNotEmpty();
            }
        }
        assertThat(lines).extracting(l -> l.get("line_no"))
            .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, lines.size()).mapToObj(String::valueOf)
                .toList());
        assertThat(lines).allSatisfy(l -> assertThat(l.get("voucher_no")).isEqualTo(export.get("voucherNo")));

        // The detail: every row on a line of the voucher, with the line's own account, side and Sponsor, and every
        // line the sum of its rows.
        Map<String, BigDecimal> sums = new TreeMap<>();
        for (Map<String, String> row : detail) {
            assertThat(row.get("line_no")).as("line_no of " + row).isNotEmpty();
            sums.merge(row.get("line_no"), new BigDecimal(row.get("amount")), BigDecimal::add);
        }
        for (Map<String, String> line : lines) {
            String no = line.get("line_no");
            assertThat(sums.get(no)).as("detail total of line " + no + " " + line.get("platform_account") + " "
                + line.get("side")).isNotNull().isEqualByComparingTo(new BigDecimal(line.get("amount")));
            assertThat(detail).filteredOn(r -> no.equals(r.get("line_no"))).isNotEmpty().allSatisfy(r -> {
                assertThat(r.get("platform_account")).isEqualTo(line.get("platform_account"));
                assertThat(r.get("side")).isEqualTo(line.get("side"));
                assertThat(r.get("sponsor_id")).isEqualTo(line.get("sponsor_id"));
                assertThat(r.get("voucher_no")).isEqualTo(line.get("voucher_no"));
            });
        }
        assertThat(sums.keySet()).hasSize(lines.size());
        return new Voucher((String) export.get("voucherNo"), lines, detail);
    }

    private static List<Map<String, String>> named(List<String> columns, List<List<String>> rows) {
        List<Map<String, String>> named = new ArrayList<>();
        for (List<String> row : rows.subList(1, rows.size())) {
            assertThat(row).hasSize(columns.size());
            Map<String, String> map = new java.util.LinkedHashMap<>();
            for (int i = 0; i < columns.size(); i++) {
                map.put(columns.get(i), row.get(i));
            }
            named.add(map);
        }
        return named;
    }

    private static Set<String> sponsorsOf(Voucher voucher, String account, String side) {
        Set<String> sponsors = new HashSet<>();
        voucher.lines().stream().filter(l -> account.equals(l.get("platform_account")) && side.equals(l.get("side")))
            .forEach(l -> sponsors.add(l.get("sponsor_id")));
        return sponsors;
    }

    /** A Sponsor's debit less credit on the voucher for an account. */
    private static BigDecimal net(Voucher voucher, String account, String sponsor) {
        BigDecimal net = BigDecimal.ZERO;
        for (Map<String, String> l : voucher.lines()) {
            if (account.equals(l.get("platform_account")) && sponsor.equals(l.get("sponsor_id"))) {
                BigDecimal amount = new BigDecimal(l.get("amount"));
                net = net.add("DEBIT".equals(l.get("side")) ? amount : amount.negate());
            }
        }
        return net;
    }

    // ---- the journal's calls (as JournalItSupport makes them; that class has another base) ----

    private Map<String, Object> export(String month) {
        return run("QB_JOURNAL_EXPORT", finance(), body("month", month));
    }

    private String reconcile(Instant at) {
        deliverEvents();
        // The operators' months are settled by a job these tests switch off (F-M12 c's waiting check would differ).
        settlementsAreNotWaitedFor();
        Reconciliation.ReconcileOutput output = (Reconciliation.ReconcileOutput) systemRun("QB_RECONCILE_RUN",
            body("scheduledTime", at.toString()));
        return output.status();
    }

    private void mapAll() {
        for (String key : QbParams.JOURNAL_ACCOUNTS) {
            param(key, "X" + key.substring(QbParams.JOURNAL_ACCOUNT_PREFIX.length()));
        }
    }

    private byte[] download(String fileId, String token) {
        return client.get().uri("/api/generated-files/{id}", fileId).header(HttpHeaders.AUTHORIZATION, token)
            .exchange().expectStatus().isOk().expectBody(byte[].class).returnResult().getResponseBody();
    }

    private static List<List<String>> csv(byte[] bytes) {
        return JournalItSupport.csv(bytes);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return JournalItSupport.sha256(bytes);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> detailFiles(Map<String, Object> export) {
        return JournalItSupport.detailFiles(export);
    }
}
