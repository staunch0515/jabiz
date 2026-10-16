package com.jabiz.quizbuks.journal;

import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.wallet.QbLedger;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** The voucher's lines and files (Q9d, D-Q9d-2, D-Q9d-4, D-Q9d-6), the periods and the mapping's startup check. */
class JournalLinesTest {

    static final Instant AT = Instant.parse("2026-10-10T01:00:00Z");

    static JournalLines.Entry entry(String tx, String account, String side, String party, long amount) {
        return new JournalLines.Entry(tx, AT, AT, account, side, party, BigDecimal.valueOf(amount), "QbTopUp", "s-1",
            null);
    }

    static final Map<String, String> MAPPED = Map.of("1110", "111", "2110", "211", "2120", "212", "4110", "411");

    static JournalLines.Group group(String account, String side, String sponsor, long amount, int transactions) {
        return new JournalLines.Group(account, side, sponsor, BigDecimal.valueOf(amount), transactions);
    }

    static Map<String, Object> summaryRow(String account, String side, String party, long amount, int transactions) {
        Map<String, Object> row = new HashMap<>();
        row.put("accountCode", account);
        row.put("direction", side);
        row.put("party", party);
        row.put("amount", BigDecimal.valueOf(amount));
        row.put("transactions", BigDecimal.valueOf(transactions));
        return row;
    }

    @Test
    void theDatabasesSumsAreNumberedByAccountSideAndSponsor() {
        // As the summary template gives them, in any order; a party on another account than 2110 is not kept.
        List<JournalLines.Group> groups = JournalLines.groups(List.of(
            summaryRow("4110", "CREDIT", null, 20, 1), summaryRow("2120", "CREDIT", "u-1", 130, 2),
            summaryRow("2110", "CREDIT", "s-b", 1_000, 1), summaryRow("2110", "CREDIT", "s-a", 500, 1),
            summaryRow("2110", "DEBIT", "s-a", 150, 2), summaryRow("1110", "DEBIT", null, 1_500, 2)));
        JournalLines.Summary summary = JournalLines.summarize(groups, MAPPED);
        assertThat(summary.lines()).extracting(JournalLines.Line::lineNo, JournalLines.Line::accountCode,
                JournalLines.Line::side, JournalLines.Line::sponsorId, l -> l.amount().longValue(),
                JournalLines.Line::transactions, JournalLines.Line::externalCode)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple(1, "1110", "DEBIT", null, 1_500L, 2, "111"),
                org.assertj.core.groups.Tuple.tuple(2, "2110", "DEBIT", "s-a", 150L, 2, "211"),
                org.assertj.core.groups.Tuple.tuple(3, "2110", "CREDIT", "s-a", 500L, 1, "211"),
                org.assertj.core.groups.Tuple.tuple(4, "2110", "CREDIT", "s-b", 1_000L, 1, "211"),
                // The takers are summed: no party on 2120.
                org.assertj.core.groups.Tuple.tuple(5, "2120", "CREDIT", null, 130L, 2, "212"),
                org.assertj.core.groups.Tuple.tuple(6, "4110", "CREDIT", null, 20L, 1, "411"));
        assertThat(summary.balanced()).isTrue();
        assertThat(summary.debitTotal()).isEqualByComparingTo("1650");
        assertThat(summary.accounts()).containsExactly("1110", "2110", "2120", "4110");
        assertThat(summary.lineNumbers()).containsEntry(JournalLines.key("2110", "CREDIT", "s-b"), 4)
            .containsEntry(JournalLines.key("2120", "CREDIT", null), 5).hasSize(6);
    }

    @Test
    void unmappedAndInvalidCodesAreFoundAndAnUnbalancedVoucherIsSeen() {
        Map<String, String> mapping = new HashMap<>(MAPPED);
        mapping.put("2120", " ");
        mapping.put("4110", "=1+1");
        mapping.remove("1110");
        List<String> accounts = List.of("1110", "2110", "2120", "4110");
        assertThat(JournalLines.unmapped(accounts, mapping)).containsExactly("1110", "2120");
        assertThat(JournalLines.invalid(accounts, mapping)).containsExactly("4110");
        assertThat(JournalLines.summarize(List.of(group("1110", "DEBIT", null, 2, 1)), mapping).balanced())
            .isFalse();
        assertThat(JournalLines.ACCOUNT_CODE.matcher("A-1.b_2").matches()).isTrue();
        assertThat(JournalLines.ACCOUNT_CODE.matcher("7").matches()).isTrue();
        assertThat(JournalLines.ACCOUNT_CODE.matcher("x".repeat(20)).matches()).isTrue();
        assertThat(JournalLines.ACCOUNT_CODE.matcher("x".repeat(21)).matches()).isFalse();
        // A letter or digit first (review L3): nothing a spreadsheet or the accounting system could take otherwise.
        for (String bad : List.of("-1", ".1", "_1", "", " 1", "1 2")) {
            assertThat(JournalLines.ACCOUNT_CODE.matcher(bad).matches()).as(bad).isFalse();
        }
    }

    @Test
    void cellsAreQuotedAndFormulasDefused() {
        assertThat(JournalLines.cell("plain")).isEqualTo("\"plain\"");
        assertThat(JournalLines.cell("")).isEqualTo("\"\"");
        assertThat(JournalLines.cell(null)).isEqualTo("\"\"");
        assertThat(JournalLines.cell("a\"b")).isEqualTo("\"a\"\"b\"");
        for (String formula : List.of("=1+1", "+1", "-1", "@SUM(A1)", "\tx", "\rx")) {
            assertThat(JournalLines.cell(formula)).as(formula).isEqualTo("\"'" + formula + "\"");
        }
    }

    @Test
    void theVoucherFileHasTheSummaryLinesAndTheDetailEveryPosting() {
        JournalPeriods.Period october = JournalPeriods.month(YearMonth.of(2026, 10));
        JournalLines.Summary summary = JournalLines.summarize(List.of(
            group("1110", "DEBIT", null, 1_000, 1), group("2110", "CREDIT", "=s", 1_000, 1)), MAPPED);
        byte[] voucher = JournalLines.voucherCsv("QBJ-2026-00001", october, summary);
        assertThat(voucher).startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        String text = new String(voucher, 3, voucher.length - 3, StandardCharsets.UTF_8);
        assertThat(text.split("\r\n")).containsExactly(
            "\"voucher_no\",\"date\",\"line_no\",\"side\",\"account_code\",\"platform_account\",\"sponsor_id\","
                + "\"amount\",\"memo\"",
            "\"QBJ-2026-00001\",\"2026-10-31\",\"1\",\"DEBIT\",\"111\",\"1110\",\"\",\"1000\","
                + "\"QuizBuks 2026-10 1110 DEBIT\"",
            "\"QBJ-2026-00001\",\"2026-10-31\",\"2\",\"CREDIT\",\"211\",\"2110\",\"'=s\",\"1000\","
                + "\"QuizBuks 2026-10 2110 CREDIT\"");
        // A part of the detail: by line, the times in Japan's (review L4), the sponsor only on 2110.
        List<JournalLines.Entry> entries = List.of(entry("t2", "1110", "DEBIT", null, 400),
            entry("t1", "2110", "CREDIT", "=s", 1_000), entry("t1", "1110", "DEBIT", null, 600));
        String detail = new String(JournalLines.detailCsv("QBJ-2026-00001", entries, summary.lineNumbers()),
            StandardCharsets.UTF_8);
        assertThat(detail.substring(1).split("\r\n")).containsExactly(
            "\"voucher_no\",\"line_no\",\"transaction_id\",\"booking_time\",\"recorded_at\",\"platform_account\","
                + "\"side\",\"sponsor_id\",\"amount\",\"source\",\"source_id\",\"reference\"",
            "\"QBJ-2026-00001\",\"1\",\"t1\",\"2026-10-10T10:00:00+09:00\",\"2026-10-10T10:00:00+09:00\",\"1110\","
                + "\"DEBIT\",\"\",\"600\",\"QbTopUp\",\"s-1\",\"\"",
            "\"QBJ-2026-00001\",\"1\",\"t2\",\"2026-10-10T10:00:00+09:00\",\"2026-10-10T10:00:00+09:00\",\"1110\","
                + "\"DEBIT\",\"\",\"400\",\"QbTopUp\",\"s-1\",\"\"",
            "\"QBJ-2026-00001\",\"2\",\"t1\",\"2026-10-10T10:00:00+09:00\",\"2026-10-10T10:00:00+09:00\",\"2110\","
                + "\"CREDIT\",\"'=s\",\"1000\",\"QbTopUp\",\"s-1\",\"\"");
        // A posting of no line of the voucher is a defect, never a silent row.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> JournalLines.detailCsv("QBJ-2026-00001",
                List.of(entry("t3", "4110", "CREDIT", null, 1)), summary.lineNumbers()))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theLimitsCutPartsAtHalfAndRefuseWhatCannotFit() {
        JournalLimits limits = new JournalLimits(800, 4_000, Duration.ofMinutes(5));
        assertThat(limits.partRows()).isEqualTo(400);
        assertThat(limits.partBytes()).isEqualTo((4_000 - JournalLimits.HEADER_BYTES) / 2);
        assertThat(limits.fits(400, limits.partBytes())).isTrue();
        assertThat(limits.fits(401, 1)).isFalse();
        assertThat(limits.fits(1, limits.partBytes() + 1)).isFalse();
        // Near: four fifths of a read in voucher lines, of a file in the voucher, or of a part in one transaction.
        assertThat(limits.near(639, 100, 319, 1)).isFalse();
        assertThat(limits.near(640, 100, 1, 1)).isTrue();
        assertThat(limits.near(1, 3_200, 1, 1)).isTrue();
        assertThat(limits.near(1, 100, 320, 1)).isTrue();
        assertThat(limits.near(1, 100, 1, limits.partBytes() * 4 / 5 + 1)).isTrue();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new JournalLimits(1, 4_000, Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class);
        // The open transactions' limit stays within half the margin: see JournalPeriods.SETTLE.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new JournalLimits(800, 4_000,
            JournalPeriods.SETTLE.dividedBy(2).plusSeconds(1))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void monthsAreJapaneseAndFollowOneAnother() {
        JournalPeriods.Period october = JournalPeriods.month(YearMonth.of(2026, 10));
        assertThat(october.from()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(october.to()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(october.start()).isEqualTo(Instant.parse("2026-09-30T15:00:00Z"));
        assertThat(october.end()).isEqualTo(Instant.parse("2026-10-31T15:00:00Z"));
        assertThat(october.label()).isEqualTo("2026-10");
        // 23:30 UTC on 31 October is 1 November in Japan.
        assertThat(JournalPeriods.monthOf(Instant.parse("2026-10-31T15:30:00Z"))).isEqualTo(YearMonth.of(2026, 11));
        assertThat(JournalPeriods.next(LocalDate.of(2026, 12, 1), AT).month()).isEqualTo(YearMonth.of(2027, 1));
        assertThat(JournalPeriods.next(null, Instant.parse("2026-02-28T16:00:00Z")).month())
            .isEqualTo(YearMonth.of(2026, 3));
        assertThat(JournalPeriods.next(null, null)).isNull();
        assertThat(JournalPeriods.voucherScope(october)).isEqualTo("2026");
        // The cut-off: a margin before the export, never before the previous one.
        assertThat(JournalPeriods.cutOff(AT, null)).isEqualTo(AT.minus(JournalPeriods.SETTLE));
        assertThat(JournalPeriods.cutOff(AT, AT.minusSeconds(1))).isEqualTo(AT.minusSeconds(1));
        assertThat(JournalPeriods.cutOff(AT, AT.minus(Duration.ofHours(1)))).isEqualTo(AT.minus(JournalPeriods.SETTLE));
    }

    @Test
    void everyAccountHasItsControlledMappingParameter() {
        assertThat(new JournalMappingCheck().check()).isEmpty();
        List<String> accounts = QbLedger.ACCOUNTS.stream().map(QbLedger.Account::code).toList();
        Set<String> declared = QbParams.ALL.stream().map(QbParams.Param::key).collect(Collectors.toSet());
        // A new account without its parameter, a parameter of no account, one neither declared nor controlled.
        List<String> withNew = new java.util.ArrayList<>(accounts);
        withNew.add("6110");
        assertThat(JournalMappingCheck.problems(withNew, declared, Set.copyOf(QbParams.CONTROLLED),
            QbParams.JOURNAL_ACCOUNTS)).hasSize(3).allSatisfy(p -> assertThat(p.location()).endsWith("6110"));
        List<String> extra = new java.util.ArrayList<>(QbParams.JOURNAL_ACCOUNTS);
        extra.add("qb.journal.account.9999");
        assertThat(JournalMappingCheck.problems(accounts, declared, Set.copyOf(QbParams.CONTROLLED), extra))
            .singleElement().satisfies(p -> assertThat(p.location()).isEqualTo("qb.journal.account.9999"));
        assertThat(JournalMappingCheck.problems(accounts, Set.of(), Set.of(), QbParams.JOURNAL_ACCOUNTS))
            .hasSize(2 * accounts.size());
    }
}
