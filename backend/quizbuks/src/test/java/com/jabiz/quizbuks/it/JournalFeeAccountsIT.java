package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.journal.JournalLines;
import com.jabiz.quizbuks.journal.JournalPeriods;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * The journal export of account 1130 (fees receivable) and 2180 (prepayments set aside for a publication): both are
 * kept per sponsor on the voucher, as 2110, 2160 and 2190 are, so the accounting system can tell whose receivable or
 * prepayment a line is; and every line of the voucher is the sum of its detail rows (each detail row carries the number
 * of the line it belongs to).
 *
 * <p>Before this, 1130 lost its sponsor in the Java stage (the templates kept it): the voucher had one line per sponsor
 * and side without the sponsor, two lines shared one key, and the detail rows of both sponsors carried the number of
 * one of them; 2180 was one line per side for all sponsors together.
 *
 * <p>The ledger is posted by hand ({@code LEDGER_POST}, the dimensions the fee processes use) because the export
 * waits for a clean reconciliation, which these postings, with no invoice or prepayment behind them, would not give.
 * The preview process (the same templates and {@link JournalLines}) shows the voucher; the detail is read from the
 * templates and written by {@link JournalLines#detailCsv} exactly as the export's detail processes do.
 */
class JournalFeeAccountsIT extends JournalItSupport {

    @Test
    @SuppressWarnings("unchecked")
    void feesReceivableAndPrepaymentsArePerSponsorAndTheDetailAddsUpToEachLine() {
        ZonedDateTime jst = now().atZone(JournalPeriods.ZONE).plusMonths(1).withDayOfMonth(1)
            .truncatedTo(ChronoUnit.DAYS);
        JournalPeriods.Period one = JournalPeriods.month(YearMonth.from(jst));
        clock.set(one.start().plus(Duration.ofDays(2)));
        String a = newSponsor(unique("s-jf-a"));
        String b = newSponsor(unique("s-jf-b"));
        credit(a, 1);
        String pubA = livePublication(a, "JF A", Map.of("regions", List.of("GLOBAL")));
        String pubB = livePublication(b, "JF B", Map.of("regions", List.of("GLOBAL")));
        Instant at = clock.instant();
        // 1130: A is charged 300 and has 150 paid from its prepayment; B is charged 500 and pays 200.
        pair(at, "fee-a", "1130", Map.of("party", a), "4120", Map.of(), 300);
        pair(at, "fee-b", "1130", Map.of("party", b), "4120", Map.of(), 500);
        pair(at, "paid-b", "1110", Map.of(), "1130", Map.of("party", b), 200);
        // 2180: A sets 400 aside and uses 150 of it; B sets 600 aside.
        pair(at, "held-a", "1110", Map.of(), "2180", prepay(a, pubA), 400);
        pair(at, "held-b", "1110", Map.of(), "2180", prepay(b, pubB), 600);
        pair(at, "used-a", "2180", prepay(a, pubA), "1130", Map.of("party", a), 150);
        clock.set(one.end().plus(Duration.ofHours(4)));
        advance(JournalPeriods.SETTLE.plusMinutes(1));

        // What the export reads and writes: the voucher's lines (the preview, the summary template and JournalLines),
        // the parts, the detail rows and the detail file, as the export's processes do.
        Map<String, Object> preview = preview();
        List<Map<String, Object>> lines = (List<Map<String, Object>>) preview.get("lines");
        String token = TestTokens.bearer(tokens, "jf", QbPermissions.JOURNAL_EXPORT);
        Map<String, Object> params = Map.of("to", one.end().toString(), "knownAt", clock.instant().toString());
        List<Map<String, Object>> groups = items("qb.system.journal-summary", token, Map.of("params", params,
            "limit", 500));
        JournalLines.Summary summary = JournalLines.summarize(JournalLines.groups(groups), Map.of());
        Map<String, Object> partParams = new HashMap<>(params);
        partParams.put("partRows", 500);
        partParams.put("partBytes", 10_000_000);
        List<Map<String, Object>> parts = items("qb.system.journal-parts", token, Map.of("params", partParams,
            "limit", 50));
        assertThat(parts).hasSize(1);
        Map<String, Object> detailParams = new HashMap<>(params);
        detailParams.put("firstTransaction", parts.getFirst().get("firstTransaction"));
        detailParams.put("lastTransaction", parts.getFirst().get("lastTransaction"));
        List<Map<String, Object>> rows = items("qb.system.journal-detail", token, Map.of("params", detailParams,
            "limit", 500));
        List<List<String>> csv = csv(JournalLines.detailCsv("V", JournalLines.entries(rows), summary.lineNumbers()));
        List<List<String>> detail = csv.subList(1, csv.size());

        // First: every row on a line of the voucher, and every line the sum of its rows (account, side and sponsor
        // the line's own).
        Map<String, BigDecimal> byLine = new TreeMap<>();
        detail.forEach(r -> {
            assertThat(r.get(1)).as("line number of " + r).isNotEmpty();
            byLine.merge(r.get(1), new BigDecimal(r.get(8)), BigDecimal::add);
        });
        for (JournalLines.Line line : summary.lines()) {
            String no = String.valueOf(line.lineNo());
            assertThat(byLine.get(no)).as("detail total of line " + no + " " + line.accountCode() + " "
                + line.side()).isNotNull().isEqualByComparingTo(line.amount());
            assertThat(detail).filteredOn(r -> no.equals(r.get(1))).allSatisfy(r -> {
                assertThat(r.get(5)).isEqualTo(line.accountCode());
                assertThat(r.get(6)).isEqualTo(line.side());
                assertThat(r.get(7)).isEqualTo(line.sponsorId() == null ? "" : line.sponsorId());
            });
        }
        assertThat(byLine).hasSize(summary.lines().size());
        assertThat(summary.lineNumbers()).hasSize(summary.lines().size());

        // The preview (the voucher's lines): 1130 and 2180 one line per sponsor and side, the sponsor named.
        assertThat(lines).filteredOn(l -> "1130".equals(l.get("accountCode")))
            .extracting(l -> l.get("side"), l -> l.get("sponsorId"), l -> money(l.get("amount")).longValue())
            .containsExactlyInAnyOrder(tuple("DEBIT", a, 300L), tuple("DEBIT", b, 500L), tuple("CREDIT", a, 150L),
                tuple("CREDIT", b, 200L));
        assertThat(lines).filteredOn(l -> "2180".equals(l.get("accountCode")))
            .extracting(l -> l.get("side"), l -> l.get("sponsorId"), l -> money(l.get("amount")).longValue(),
                l -> ((Number) l.get("transactions")).intValue())
            .containsExactlyInAnyOrder(tuple("DEBIT", a, 150L, 1), tuple("CREDIT", a, 400L, 1),
                tuple("CREDIT", b, 600L, 1));

        // The templates: the summary keeps the sponsor of both accounts ...
        assertThat(groups).filteredOn(g -> "2180".equals(g.get("accountCode")))
            .extracting(g -> g.get("direction") + ":" + g.get("party") + ":" + money(g.get("amount")).intValue())
            .containsExactlyInAnyOrder("DEBIT:" + a + ":150", "CREDIT:" + a + ":400", "CREDIT:" + b + ":600");
        assertThat(summary.lines().stream().filter(l -> List.of("1130", "2180").contains(l.accountCode())))
            .hasSize(7).allSatisfy(l -> assertThat(l.sponsorId()).isIn(a, b));

        // ... and so do the parts and the detail (a row per transaction, account, side and sponsor).
        assertThat(parts.getFirst()).containsEntry("partNo", 1).containsEntry("transactions", 7)
            .containsEntry("rowCount", 14);
        assertThat(preview).containsEntry("detailRows", 14).containsEntry("detailParts", 1);
        assertThat(rows).hasSize(14);
        assertThat(rows).filteredOn(r -> List.of("1130", "2180").contains(r.get("accountCode"))).hasSize(7)
            .allSatisfy(r -> assertThat(r.get("party")).isIn(a, b));

        // The detail file, as the export writes it: the sponsor on every 1130 and 2180 row.
        assertThat(detail).hasSize(14);
        assertThat(detail).filteredOn(r -> List.of("1130", "2180").contains(r.get(5))).hasSize(7)
            .allSatisfy(r -> assertThat(r.get(7)).isIn(a, b));
    }

    String pair(Instant at, String reference, String debit, Map<String, String> debitDims, String credit,
        Map<String, String> creditDims, long amount) {
        return post(at, reference, List.of(
            body("accountCode", debit, "direction", "DEBIT", "amount", amount, "dimensions", debitDims),
            body("accountCode", credit, "direction", "CREDIT", "amount", amount, "dimensions", creditDims)));
    }

    static Map<String, String> prepay(String sponsor, String publication) {
        return Map.of("party", sponsor, "publication", publication, "purpose", "AUDIENCE");
    }
}
