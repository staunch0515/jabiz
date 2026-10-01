package com.jabiz.finance.gl;

import com.jabiz.entity.Violation;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class JournalValidatorTest {

    private static final Map<String, JournalValidator.Account> ACCOUNTS = Map.of(
        "6400", new JournalValidator.Account("6400", true, false, null, null),
        "2100", new JournalValidator.Account("2100", true, false, null, null),
        "1010", new JournalValidator.Account("1010", true, false, "BANK", null),
        "6000", new JournalValidator.Account("6000", true, true, null, null),
        "6900", new JournalValidator.Account("6900", false, false, null, null),
        "6100", new JournalValidator.Account("6100", true, false, null, "department"));
    private static final JournalValidator.Dimensions DIMENSIONS =
        new JournalValidator.Dimensions(Set.of("SALES"), Set.of("CHI"));

    private static JournalValidator.Line debit(String account, String amount) {
        return new JournalValidator.Line(account, new BigDecimal(amount), null, null, null, null);
    }

    private static JournalValidator.Line credit(String account, String amount) {
        return new JournalValidator.Line(account, null, new BigDecimal(amount), null, null, null);
    }

    private static List<String> codes(List<Violation> violations) {
        return violations.stream().map(Violation::ruleCode).toList();
    }

    private static List<Violation> check(List<JournalValidator.Line> lines, boolean exception) {
        return JournalValidator.checkForPosting(lines, ACCOUNTS, DIMENSIONS, exception);
    }

    @Test
    void aBalancedEntryOnOrdinaryAccountsPasses() {
        assertThat(check(List.of(debit("6400", "25000.00"), credit("2100", "25000.00")), false)).isEmpty();
    }

    /** FIN-GL-011 acceptance 1: the difference 0.01 is shown. */
    @Test
    void anUnbalancedEntryShowsTheDifference() {
        List<Violation> problems = check(List.of(debit("6400", "25000.00"), credit("2100", "24999.99")), false);
        assertThat(problems).singleElement().satisfies(v -> {
            assertThat(v.ruleCode()).isEqualTo(JournalValidator.UNBALANCED);
            assertThat(v.params()).containsEntry("difference", new BigDecimal("0.01"));
            assertThat(v.message()).contains("differ by 0.01");
        });
    }

    @Test
    void everyProblemIsReportedAtOnce() {
        List<Violation> problems = check(List.of(debit("9999", "10"), debit("6000", "1.005"),
            credit("6900", "5"), new JournalValidator.Line("6400", new BigDecimal("1"), new BigDecimal("1"), null,
                null, null), credit("6100", "3"), new JournalValidator.Line("2100", null, null, null, "MKT", "NYC")),
            false);
        assertThat(codes(problems)).contains(JournalValidator.ACCOUNT_UNKNOWN, JournalValidator.ACCOUNT_SUMMARY,
            JournalValidator.ACCOUNT_INACTIVE, JournalValidator.LINE_AMOUNT, JournalValidator.DIMENSION_REQUIRED,
            JournalValidator.DIMENSION_INVALID, JournalValidator.UNBALANCED);
        assertThat(problems).filteredOn(v -> v.ruleCode().equals(JournalValidator.LINE_AMOUNT))
            .extracting(Violation::field).containsExactly("lines[1].debit", "lines[3].debit", "lines[5].credit");
        assertThat(problems).filteredOn(v -> v.ruleCode().equals(JournalValidator.DIMENSION_INVALID)).hasSize(2);
    }

    /** FIN-GL-005: a control account only with the controller's exception. */
    @Test
    void aControlAccountNeedsTheException() {
        List<JournalValidator.Line> lines = List.of(debit("2100", "15000.00"), credit("1010", "15000.00"));
        assertThat(codes(check(lines, false))).containsExactly(JournalValidator.CONTROL_ACCOUNT);
        assertThat(check(lines, true)).isEmpty();
    }

    @Test
    void anEntryHasTwoToFiveHundredLines() {
        assertThat(codes(check(List.of(debit("6400", "0.00")), false))).contains(JournalValidator.TOO_FEW_LINES);
        List<JournalValidator.Line> many = new ArrayList<>();
        for (int i = 0; i < JournalValidator.MAX_LINES + 1; i++) {
            many.add(debit("6400", "1"));
        }
        assertThat(codes(JournalValidator.checkLines(many))).containsExactly(JournalValidator.TOO_MANY_LINES);
        // A draft need not balance or name known accounts yet.
        assertThat(JournalValidator.checkLines(List.of(debit("nope", "1.00")))).isEmpty();
        assertThat(check(List.of(new JournalValidator.Line("6100", new BigDecimal("2"), null, null, "SALES", "CHI"),
            credit("2100", "2")), false)).isEmpty();
    }

    /** FIN-GL-011 acceptance 2, per entry: any entry the checks pass has equal debits and credits. */
    @Property
    void whatPassesBalances(@ForAll @Size(min = 1, max = 30) List<@IntRange(min = 1, max = 10_000_000) Integer> cents) {
        List<JournalValidator.Line> lines = new ArrayList<>();
        long total = 0;
        for (int c : cents) {
            lines.add(debit("6400", BigDecimal.valueOf(c, 2).toPlainString()));
            total += c;
        }
        lines.add(credit("2100", BigDecimal.valueOf(total, 2).toPlainString()));
        assertThat(check(lines, false)).isEmpty();
        JournalValidator.Totals totals = JournalValidator.totals(lines);
        assertThat(totals.debit()).isEqualByComparingTo(totals.credit());
        assertThat(lines.stream().map(JournalValidator.Line::signed).reduce(BigDecimal.ZERO, BigDecimal::add))
            .isEqualByComparingTo("0");
    }
}
