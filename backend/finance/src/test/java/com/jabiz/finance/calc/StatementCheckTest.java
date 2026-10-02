package com.jabiz.finance.calc;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** What a statement must satisfy and the keys its lines are stored under (FIN-BK-003). */
class StatementCheckTest {

    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO = LocalDate.of(2026, 1, 31);

    private static StatementCheck.Line line(String day, String reference, String text, String amount) {
        return new StatementCheck.Line(LocalDate.parse(day), reference, text, new BigDecimal(amount));
    }

    private static final List<StatementCheck.Line> JANUARY = List.of(
        line("2026-01-03", "BNK-0001", "CHECK 1045", "-3200.00"),
        line("2026-01-05", "BNK-0002", "DEPOSIT ACME ROBOTICS INC", "32475.00"),
        line("2026-01-08", "BNK-0003", "ACH DEBIT BATCH 0001", "-32300.00"),
        line("2026-01-31", "BNK-0009", "ACCOUNT SERVICE FEE", "-45.00"));

    @Test
    void aStatementWhoseLinesMeetItsClosingBalancePasses() {
        assertThat(StatementCheck.net(JANUARY)).isEqualByComparingTo("-3070.00");
        assertThat(StatementCheck.check(FROM, TO, new BigDecimal("253200.00"), JANUARY, new BigDecimal("250130.00")))
            .isEmpty();
    }

    @Test
    void linesThatMissTheClosingBalanceAreReportedWithTheDifference() {
        List<StatementCheck.Problem> problems = StatementCheck.check(FROM, TO, new BigDecimal("253200.00"), JANUARY,
            new BigDecimal("250140.00"));
        assertThat(problems).singleElement().satisfies(p -> {
            assertThat(p.code()).isEqualTo(StatementCheck.NOT_BALANCED);
            assertThat(p.message()).contains("difference -10.00");
        });
    }

    @Test
    void linesOutsideTheDaysAndReferencesTwiceAreReported() {
        List<StatementCheck.Line> lines = new ArrayList<>(JANUARY);
        lines.add(line("2026-02-01", "BNK-0011", "LATE", "0.00"));
        lines.add(line("2026-01-10", "bnk-0001 ", "AGAIN", "0.00"));
        assertThat(StatementCheck.check(FROM, TO, new BigDecimal("253200.00"), lines, new BigDecimal("250130.00")))
            .extracting(StatementCheck.Problem::code, StatementCheck.Problem::line)
            .containsExactly(org.assertj.core.groups.Tuple.tuple(StatementCheck.OUTSIDE, 4),
                org.assertj.core.groups.Tuple.tuple(StatementCheck.DUPLICATE, 5));
        assertThat(StatementCheck.check(TO, FROM, BigDecimal.ZERO, List.of(), BigDecimal.ZERO))
            .extracting(StatementCheck.Problem::code).containsExactly(StatementCheck.DAYS);
    }

    @Test
    void aLineIsKeyedByTheBanksReferenceWhatEverItsLayout() {
        assertThat(StatementCheck.keys(List.of(line("2026-01-03", " bnk-0001", "CHECK 1045", "-3200"))))
            .containsExactly("REF:BNK-0001");
        // The same reference sent in another file with another description is the same line.
        assertThat(StatementCheck.keys(List.of(line("2026-01-04", "BNK-0001", "CHK 1045", "-3200.00"))))
            .containsExactly("REF:BNK-0001");
    }

    @Test
    void withoutAReferenceEqualLinesOnOneDayStayDistinctAndStable() {
        List<StatementCheck.Line> fees = List.of(line("2026-01-31", null, "Service  fee", "-45.00"),
            line("2026-01-31", "", "SERVICE FEE", "-45.0"));
        List<String> keys = StatementCheck.keys(fees);
        assertThat(keys).hasSize(2).doesNotHaveDuplicates().allMatch(k -> k.startsWith("H:") && k.length() == 42);
        // Read again (in the same order) the same keys come out: the statement is stored once.
        assertThat(StatementCheck.keys(fees)).isEqualTo(keys);
        // The first of the two in one file is the first of the two in another.
        assertThat(StatementCheck.keys(fees.subList(0, 1))).containsExactly(keys.getFirst());
    }

    @Property(tries = 200)
    void keysOfDistinctLinesNeverCollide(@ForAll @IntRange(min = 1, max = 60) int count) {
        List<StatementCheck.Line> lines = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            lines.add(line("2026-01-" + String.format("%02d", 1 + i % 28), i % 3 == 0 ? "R" + i : null,
                "FEE " + (i % 5), (i % 7) + ".00"));
        }
        assertThat(new HashSet<>(StatementCheck.keys(lines))).hasSize(count);
    }

    @Test
    void theLastFourOfAnAccountNumber() {
        assertThat(StatementCheck.last4("000123456789")).isEqualTo("6789");
        assertThat(StatementCheck.last4("DE89 3704 0044 0532 0130 00")).isEqualTo("3000");
        assertThat(StatementCheck.last4("12")).isEqualTo("12");
        assertThat(StatementCheck.last4(null)).isNull();
    }
}
