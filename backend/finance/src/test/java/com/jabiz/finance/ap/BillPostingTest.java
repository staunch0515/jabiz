package com.jabiz.finance.ap;

import com.jabiz.finance.gl.JournalValidator;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The general ledger lines of bills and vendor credits (FIN-AP-004, FIN-TX-007). */
class BillPostingTest {

    @Test
    void aBillDebitsItsAccountsAndCreditsPayablesWithUseTaxOnTheLinesAccount() {
        BillPosting.Result result = BillPosting.lines(false, List.of(
            new BillPosting.Line(new BigDecimal("1000.00"), "6500", null, null, new BigDecimal("82.50")),
            new BillPosting.Line(new BigDecimal("200.00"), "6500", null, null, null),
            new BillPosting.Line(new BigDecimal("300.00"), "6400", "ADMIN", null, null)), "2000", "2210", "Bill", "Use tax");
        assertThat(result.total()).isEqualByComparingTo("1500.00");
        assertThat(result.useTax()).isEqualByComparingTo("82.50");
        assertThat(result.lines()).extracting(l -> l.accountCode() + " " + l.debit() + " " + l.credit() + " "
            + l.department()).containsExactly("2000 null 1500.00 null", "6500 1282.50 null null",
            "6400 300.00 null ADMIN", "2210 null 82.50 null");
    }

    @Test
    void aVendorCreditGoesTheOtherWay() {
        BillPosting.Result result = BillPosting.lines(true, List.of(new BillPosting.Line(new BigDecimal("500.00"),
            "6600", null, null, null)), "2000", null, "Credit", null);
        assertThat(result.lines()).extracting(l -> l.accountCode() + " " + l.debit() + " " + l.credit())
            .containsExactly("2000 500.00 null", "6600 null 500.00");
    }

    @Property
    void theEntryBalances(@ForAll @Size(min = 1, max = 8) List<@IntRange(min = 1, max = 1_000_000) Integer> cents,
        @ForAll boolean credit) {
        List<BillPosting.Line> lines = cents.stream().map(c -> new BillPosting.Line(BigDecimal.valueOf(c, 2),
            c % 3 == 0 ? "6400" : "6500", null, null, c % 2 == 0 ? BigDecimal.valueOf(c / 10, 2) : null)).toList();
        BillPosting.Result result = BillPosting.lines(credit, lines, "2000", "2210", null, null);
        BigDecimal debits = result.lines().stream().map(JournalValidator.Line::debit).filter(d -> d != null)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credits = result.lines().stream().map(JournalValidator.Line::credit).filter(c -> c != null)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(debits).isEqualByComparingTo(credits);
    }
}
