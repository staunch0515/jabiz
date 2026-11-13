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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M14 G12 (docs/quizbuks/plans/impl/F-M14.md §1.4): what the journal export produces today for account 1130 (fees
 * receivable) and 2180 (prepayments), pinned as assertions. It documents the current behaviour; it does not say what it
 * should be.
 *
 * <p><b>Finding (answers requester question 4).</b> <em>1130</em>: the summary template ({@code journal-summary}), the
 * detail template ({@code journal-detail}) and the parts template ({@code journal-parts}) all keep the sponsor of a 1130
 * line (their {@code IN ('2110', '2160', '2190', '1130')}), so the database gives one group per sponsor and side. But
 * {@code JournalLines.PER_SPONSOR} does not list 1130: the Java drops the sponsor, so the voucher has one line per
 * sponsor and side <em>without the sponsor</em> ({@code sponsor_id} empty, several lines with the same account and
 * side), and the detail CSV has the sponsor empty on every 1130 row as well. Worse, the detail finds its voucher line
 * by the key (account, side, sponsor): the lines of two sponsors share the key, so all their rows get the number of one
 * of the lines and the other line has no detail row at all (the detail rows of a line no longer add up to it). <em>2180</em>:
 * neither the templates nor the Java keep the sponsor (or the publication or the purpose): the voucher has one line per
 * side with the total of all sponsors, and its detail rows have an empty {@code sponsor_id}; those rows add up to
 * their line. So neither account is listed per sponsor on the voucher as 2110, 2160 and 2190 are (the accounting system
 * cannot tell whose receivable or prepayment a line is), and 1130's detail is inconsistent with its voucher.
 *
 * <p>The ledger is posted by hand ({@code LEDGER_POST}, the dimensions the fee processes use) because the export
 * waits for a clean reconciliation, which these postings, with no invoice or prepayment behind them, would not give.
 * The preview process (the same templates and {@link JournalLines}) shows the voucher; the detail is read from the
 * templates and written by {@link JournalLines#detailCsv} exactly as the export's detail processes do.
 */
class JournalFeeAccountsIT extends JournalItSupport {

    @Test
    @SuppressWarnings("unchecked")
    void thePreviewAndTheDetailOfFeesReceivableAndPrepayments() {
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

        // The preview (the voucher's lines): 2110 per sponsor, 1130 one line per sponsor and side with no sponsor named,
        // 2180 one line per side for everyone together.
        Map<String, Object> preview = preview();
        List<Map<String, Object>> lines = (List<Map<String, Object>>) preview.get("lines");
        assertThat(lines).filteredOn(l -> "2110".equals(l.get("accountCode"))).singleElement()
            .satisfies(l -> assertThat(l).containsEntry("sponsorId", a).containsEntry("side", "CREDIT"));
        assertThat(lines).filteredOn(l -> "1130".equals(l.get("accountCode"))).hasSize(4)
            .allSatisfy(l -> assertThat(l).containsEntry("sponsorId", null));
        assertThat(amounts(lines, "1130", "DEBIT")).containsExactly(300L, 500L);
        assertThat(amounts(lines, "1130", "CREDIT")).containsExactly(150L, 200L);
        assertThat(lines).filteredOn(l -> "2180".equals(l.get("accountCode")))
            .extracting(l -> l.get("side"), l -> money(l.get("amount")).longValue(), l -> l.get("sponsorId"),
                l -> ((Number) l.get("transactions")).intValue())
            .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("DEBIT", 150L, null, 1),
                org.assertj.core.groups.Tuple.tuple("CREDIT", 1000L, null, 2));

        // The templates: the summary and the detail keep the sponsor of a 1130 line, and not of a 2180 line.
        String token = TestTokens.bearer(tokens, "jf", QbPermissions.JOURNAL_EXPORT);
        Map<String, Object> params = Map.of("to", one.end().toString(), "knownAt", clock.instant().toString());
        List<Map<String, Object>> groups = items("qb.system.journal-summary", token, Map.of("params", params,
            "limit", 500));
        assertThat(groups).filteredOn(g -> "1130".equals(g.get("accountCode")))
            .extracting(g -> g.get("direction") + ":" + g.get("party") + ":" + money(g.get("amount")).intValue())
            .containsExactlyInAnyOrder("DEBIT:" + a + ":300", "DEBIT:" + b + ":500", "CREDIT:" + a + ":150",
                "CREDIT:" + b + ":200");
        assertThat(groups).filteredOn(g -> "2180".equals(g.get("accountCode")))
            .allSatisfy(g -> assertThat(g).containsEntry("party", null));
        // ... and the Java stage builds the voucher from them without the 1130 sponsor.
        JournalLines.Summary summary = JournalLines.summarize(JournalLines.groups(groups), Map.of());
        assertThat(summary.lines().stream().filter(l -> "1130".equals(l.accountCode())))
            .hasSize(4).allSatisfy(l -> assertThat(l.sponsorId()).isNull());

        // The parts: one part holding every row of the detail (a row per transaction, account, side and 1130 sponsor).
        Map<String, Object> partParams = new HashMap<>(params);
        partParams.put("partRows", 500);
        partParams.put("partBytes", 10_000_000);
        List<Map<String, Object>> parts = items("qb.system.journal-parts", token, Map.of("params", partParams,
            "limit", 50));
        assertThat(parts).singleElement().satisfies(part -> {
            assertThat(part).containsEntry("partNo", 1).containsEntry("transactions", 7)
                .containsEntry("rowCount", 14);
            assertThat(preview).containsEntry("detailRows", 14).containsEntry("detailParts", 1);
        });
        Map<String, Object> detailParams = new HashMap<>(params);
        detailParams.put("firstTransaction", parts.getFirst().get("firstTransaction"));
        detailParams.put("lastTransaction", parts.getFirst().get("lastTransaction"));
        List<Map<String, Object>> rows = items("qb.system.journal-detail", token, Map.of("params", detailParams,
            "limit", 500));
        assertThat(rows).hasSize(14);
        assertThat(rows).filteredOn(r -> "1130".equals(r.get("accountCode"))).hasSize(4)
            .allSatisfy(r -> assertThat(r.get("party")).isIn(a, b));
        assertThat(rows).filteredOn(r -> "2180".equals(r.get("accountCode"))).hasSize(3)
            .allSatisfy(r -> assertThat(r.get("party")).isNull());

        // The detail file, as the export writes it: the sponsor is empty on every 1130 and 2180 row ...
        List<List<String>> csv = csv(JournalLines.detailCsv("V", JournalLines.entries(rows), summary.lineNumbers()));
        List<List<String>> detail = csv.subList(1, csv.size());
        assertThat(detail).hasSize(14);
        assertThat(detail).filteredOn(r -> List.of("1130", "2180").contains(r.get(5)))
            .allSatisfy(r -> assertThat(r.get(7)).isEmpty());
        assertThat(detail).filteredOn(r -> "2110".equals(r.get(5))).singleElement()
            .satisfies(r -> assertThat(r.get(7)).isEqualTo(a));

        // ... 2180's rows add up to their lines; 1130's do not: two lines of a side share one number, the rows of both
        // sponsors carry the same one, and the other line has none.
        Map<String, BigDecimal> byLine = new TreeMap<>();
        detail.forEach(r -> byLine.merge(r.get(1), new BigDecimal(r.get(8)), BigDecimal::add));
        for (JournalLines.Line line : summary.lines()) {
            if ("2180".equals(line.accountCode()) || "2110".equals(line.accountCode())) {
                assertThat(byLine.get(String.valueOf(line.lineNo()))).as("line " + line.lineNo())
                    .isEqualByComparingTo(line.amount());
            }
        }
        for (String side : List.of("DEBIT", "CREDIT")) {
            List<JournalLines.Line> receivable = summary.lines().stream()
                .filter(l -> "1130".equals(l.accountCode()) && side.equals(l.side())).toList();
            assertThat(receivable).hasSize(2);
            List<String> numbers = detail.stream().filter(r -> "1130".equals(r.get(5)) && side.equals(r.get(6)))
                .map(r -> r.get(1)).distinct().toList();
            assertThat(numbers).as("1130 " + side + " detail rows share one line number").hasSize(1);
            long linesWithRows = receivable.stream().filter(l -> byLine.containsKey(String.valueOf(l.lineNo()))).count();
            assertThat(linesWithRows).as("1130 " + side + " lines that have detail rows").isEqualTo(1);
            // The rows of both sponsors, 800 or 350, against the line they landed on, 300 or 500 (150 or 200).
            BigDecimal rowsTotal = byLine.get(numbers.getFirst());
            BigDecimal lineAmount = receivable.stream().filter(l -> numbers.getFirst().equals(String.valueOf(l.lineNo())))
                .findFirst().orElseThrow().amount();
            assertThat(rowsTotal).isEqualByComparingTo("DEBIT".equals(side) ? "800" : "350");
            assertThat(rowsTotal).isNotEqualByComparingTo(lineAmount);
        }
    }

    static List<Long> amounts(List<Map<String, Object>> lines, String account, String side) {
        List<Long> amounts = new ArrayList<>();
        lines.stream().filter(l -> account.equals(l.get("accountCode")) && side.equals(l.get("side")))
            .forEach(l -> amounts.add(money(l.get("amount")).longValue()));
        amounts.sort(Long::compareTo);
        return amounts;
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
