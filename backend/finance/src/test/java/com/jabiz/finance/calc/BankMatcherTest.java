package com.jabiz.finance.calc;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** What matching proposes (FIN-BK-004), on the sample company's January (bank-statement-2026-01.csv, FIN-EXP-02). */
class BankMatcherTest {

    private static BankMatcher.Line line(String id, String day, String amount, String text) {
        return new BankMatcher.Line(id, LocalDate.parse(day), new BigDecimal(amount), id, text);
    }

    private static BankMatcher.Item item(String id, String day, String amount, String document, String party,
        String check, String run) {
        return new BankMatcher.Item(id.startsWith("CHK") ? "OPENING" : "LEDGER", id, LocalDate.parse(day),
            new BigDecimal(amount), document, party, check, run);
    }

    /** The January statement's lines but the opening and closing balances. */
    static final List<BankMatcher.Line> STATEMENT = List.of(
        line("BNK-0001", "2026-01-03", "-3200.00", "CHECK 1045"),
        line("BNK-0002", "2026-01-05", "32475.00", "DEPOSIT ACME ROBOTICS INC"),
        line("BNK-0003", "2026-01-08", "-32300.00", "ACH DEBIT BATCH 0001 NORTHWIND AP"),
        line("BNK-0004", "2026-01-15", "-15000.00", "ACH DEBIT PAYROLLCO BONUS"),
        line("BNK-0005", "2026-01-16", "24025.00", "DEPOSIT CASCADE MACHINING"),
        line("BNK-0006", "2026-01-20", "-3300.00", "ACH DEBIT TX COMPTROLLER STX"),
        line("BNK-0007", "2026-01-22", "-19000.00", "ACH DEBIT BATCH 0002 NORTHWIND AP"),
        line("BNK-0008", "2026-01-26", "20000.00", "DEPOSIT LONE STAR DISTRIB"),
        line("BNK-0009", "2026-01-31", "-45.00", "ACCOUNT SERVICE FEE"),
        line("BNK-0010", "2026-01-31", "-300.00", "LOAN INTEREST LOC 50000"));

    /** The cash account's January items, as the books have them, and the check outstanding at the cutover. */
    static final List<BankMatcher.Item> BOOKS = List.of(
        item("CHK-1045", "2025-12-28", "-3200.00", "CHK-1045", null, null, null),
        item("RCPT-0001", "2026-01-05", "32475.00", "RCPT-0001", "Acme Robotics Inc.", null, null),
        item("PMT-0001", "2026-01-08", "-28300.00", "PMT-0001", "Precision Parts Co.", null, "PAY-RUN-01"),
        item("PMT-0002", "2026-01-08", "-4000.00", "PMT-0002", "City Power & Light", null, "PAY-RUN-01"),
        item("JE-0001", "2026-01-15", "-15000.00", "JE-0001", null, null, null),
        item("RCPT-0002", "2026-01-16", "24025.00", "RCPT-0002", "Cascade Machining LLC", null, null),
        item("PMT-0003", "2026-01-20", "-3300.00", "PMT-0003", "Texas Comptroller", null, "PAY-RUN-03"),
        item("PMT-0004", "2026-01-22", "-9000.00", "PMT-0004", "Delta Consulting LLC", null, "PAY-RUN-02"),
        item("PMT-0005", "2026-01-22", "-8500.00", "PMT-0005", "Metro Properties", null, "PAY-RUN-02"),
        item("PMT-0006", "2026-01-22", "-1500.00", "PMT-0006", "J. Rivera", null, "PAY-RUN-02"),
        item("RCPT-0003", "2026-01-25", "20000.00", "RCPT-0003", "Lone Star Distribution", null, null),
        item("PAYROLL-2601", "2026-01-30", "-45000.00", "PAYROLL-2601", null, null, null));

    private static Map<String, List<String>> matched(List<BankMatcher.Proposal> proposals) {
        return proposals.stream().collect(Collectors.toMap(p -> p.line().id(),
            p -> p.items().stream().map(BankMatcher.Item::id).toList()));
    }

    @Test
    void januaryGivesTheEightMatchesTheStatementExpects() {
        List<BankMatcher.Proposal> proposals = BankMatcher.propose(STATEMENT, BOOKS, 3);
        // FIN-BK-004 acceptance 1: the CSV's expected_match column.
        assertThat(matched(proposals)).containsExactlyInAnyOrderEntriesOf(Map.of(
            "BNK-0001", List.of("CHK-1045"),
            "BNK-0002", List.of("RCPT-0001"),
            "BNK-0003", List.of("PMT-0001", "PMT-0002"),
            "BNK-0004", List.of("JE-0001"),
            "BNK-0005", List.of("RCPT-0002"),
            "BNK-0006", List.of("PMT-0003"),
            "BNK-0007", List.of("PMT-0004", "PMT-0005", "PMT-0006"),
            "BNK-0008", List.of("RCPT-0003")));
        // The fee and the interest are the bank's own; PAYROLL-2601 is not on the statement yet.
        assertThat(proposals).extracting(p -> p.line().id()).doesNotContain("BNK-0009", "BNK-0010");
        assertThat(proposals).flatExtracting(BankMatcher.Proposal::items).extracting(BankMatcher.Item::id)
            .doesNotContain("PAYROLL-2601");
        assertThat(proposals).extracting(p -> p.line().id()).containsExactly("BNK-0001", "BNK-0002", "BNK-0003",
            "BNK-0004", "BNK-0005", "BNK-0006", "BNK-0007", "BNK-0008");
    }

    @Test
    void eachProposalSaysHowSureAndWhy() {
        Map<String, BankMatcher.Proposal> byLine = BankMatcher.propose(STATEMENT, BOOKS, 3).stream()
            .collect(Collectors.toMap(p -> p.line().id(), p -> p));
        // A check cashed six days after it was written: its number names it.
        assertThat(byLine.get("BNK-0001").reasons()).containsExactly("amount equal", "6 days apart", "check 1045");
        assertThat(byLine.get("BNK-0001").confidence()).isEqualTo(75);
        assertThat(byLine.get("BNK-0002").reasons()).containsExactly("amount equal", "same day", "name ACME ROBOTICS");
        assertThat(byLine.get("BNK-0002").confidence()).isEqualTo(85);
        assertThat(byLine.get("BNK-0003").reasons()).containsExactly("batch PAY-RUN-01 of 2 payments adds up",
            "same day");
        assertThat(byLine.get("BNK-0004").reasons()).containsExactly("amount equal", "same day");
        assertThat(byLine.get("BNK-0004").confidence()).isEqualTo(70);
        assertThat(byLine.get("BNK-0008").reasons()).containsExactly("amount equal", "1 day apart", "name LONE STAR");
        assertThat(byLine.get("BNK-0008").confidence()).isEqualTo(75);
    }

    @Test
    void outsideTheWindowNothingIsProposedButACheckWhoseNumberTheLineNames() {
        List<BankMatcher.Line> late = List.of(line("L1", "2026-01-20", "32475.00", "DEPOSIT ACME"),
            line("L2", "2026-03-01", "-3200.00", "CHECK 1045"));
        assertThat(matched(BankMatcher.propose(late, BOOKS, 3))).containsOnlyKeys("L2");
        // A check cannot be cashed before it is written, give or take the window.
        List<BankMatcher.Line> early = List.of(line("L3", "2025-12-01", "-3200.00", "CHECK 1045"));
        assertThat(BankMatcher.propose(early, BOOKS, 3)).isEmpty();
        // With a wider window the deposit is proposed, two weeks apart.
        assertThat(matched(BankMatcher.propose(late, BOOKS, 15))).containsEntry("L1", List.of("RCPT-0001"));
    }

    @Test
    void equalCandidatesLowerTheConfidenceAndSaySo() {
        List<BankMatcher.Item> twins = List.of(item("A", "2026-01-10", "-500.00", "JE-1", null, null, null),
            item("B", "2026-01-10", "-500.00", "JE-2", null, null, null));
        List<BankMatcher.Proposal> proposals = BankMatcher.propose(List.of(line("L", "2026-01-10", "-500.00",
            "TRANSFER")), twins, 3);
        assertThat(proposals).singleElement().satisfies(p -> {
            assertThat(p.confidence()).isEqualTo(40);
            assertThat(p.alike()).isEqualTo(1);
            assertThat(p.reasons()).contains("1 other is alike");
        });
        // Two lines as good for one item: the item is proposed once, saying the other line is alike.
        List<BankMatcher.Proposal> lines = BankMatcher.propose(List.of(line("L1", "2026-01-10", "-500.00", "X"),
            line("L2", "2026-01-10", "-500.00", "Y")), twins.subList(0, 1), 3);
        assertThat(lines).singleElement().satisfies(p -> {
            assertThat(p.line().id()).isEqualTo("L1");
            assertThat(p.confidence()).isEqualTo(40);
        });
        // Two lines and two items: each item once, each line once.
        assertThat(BankMatcher.propose(List.of(line("L2", "2026-01-11", "-500.00", "Y"),
            line("L1", "2026-01-10", "-500.00", "X")), List.of(twins.get(1), twins.get(0)), 3))
            .isEqualTo(BankMatcher.propose(List.of(line("L1", "2026-01-10", "-500.00", "X"),
                line("L2", "2026-01-11", "-500.00", "Y")), twins, 3));
        List<BankMatcher.Proposal> both = BankMatcher.propose(List.of(line("L1", "2026-01-10", "-500.00", "X"),
            line("L2", "2026-01-11", "-500.00", "Y")), twins, 3);
        assertThat(both).hasSize(2);
        assertThat(both).flatExtracting(BankMatcher.Proposal::items).extracting(BankMatcher.Item::id)
            .containsExactlyInAnyOrder("A", "B");
    }

    @Test
    void checkNumbersAndNamesAreReadForgivingly() {
        assertThat(BankMatcher.checkNumber("CHK-01045")).isEqualTo("1045");
        assertThat(BankMatcher.checkNumber("check no. 10001")).isEqualTo("10001");
        assertThat(BankMatcher.checkNumber("CHECKING DEPOSIT")).isNull();
        assertThat(BankMatcher.sharedWords("DEPOSIT CASCADE MACHINING", "Cascade Machining LLC"))
            .containsExactly("CASCADE", "MACHINING");
        assertThat(BankMatcher.sharedWords("ACH DEBIT PAYROLLCO BONUS", "The ACH Company Inc.")).isEmpty();
    }

    @Property(tries = 200)
    void aLineWithOneItemOfItsAmountAndDayIsAlwaysProposedIt(@ForAll @IntRange(min = 1, max = 40) int count,
        @ForAll @IntRange(min = 0, max = 30) int day) {
        List<BankMatcher.Line> lines = new ArrayList<>();
        List<BankMatcher.Item> items = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String amount = (i + 1) + "1.00";
            LocalDate date = LocalDate.of(2026, 1, 1).plusDays((day + i) % 31);
            lines.add(new BankMatcher.Line("L" + i, date, new BigDecimal(amount), null, "LINE " + i));
            items.add(new BankMatcher.Item("LEDGER", "I" + i, date, new BigDecimal(amount), "D" + i, null, null,
                null));
        }
        List<BankMatcher.Proposal> proposals = BankMatcher.propose(lines, items, 3);
        assertThat(proposals).hasSize(count).allSatisfy(p -> {
            assertThat(p.items()).singleElement().satisfies(i -> assertThat(i.id().substring(1))
                .isEqualTo(p.line().id().substring(1)));
            assertThat(p.confidence()).isEqualTo(70);
        });
    }
}
