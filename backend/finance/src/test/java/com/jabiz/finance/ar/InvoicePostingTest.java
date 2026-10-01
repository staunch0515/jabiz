package com.jabiz.finance.ar;

import com.jabiz.finance.gl.JournalValidator;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.BigRange;
import net.jqwik.api.constraints.Scale;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The general ledger lines of invoices and credit memos (FIN-AR-003, 006). */
class InvoicePostingTest {

    private static List<String> lines(InvoicePosting.Result result) {
        return result.lines().stream().map(l -> l.accountCode() + " " + (l.debit() != null ? l.debit()
            : "(" + l.credit() + ")")).toList();
    }

    @Test
    void inv1004DebitsTheReceivableWithRevenueAndTax() {
        InvoicePosting.Result result = InvoicePosting.lines(false, List.of(
            new InvoicePosting.Line(new BigDecimal("40000.00"), "4000", null, null),
            new InvoicePosting.Line(new BigDecimal("10000.00"), "4100", null, null)),
            new BigDecimal("3300.00"), BigDecimal.ONE, "1200", "2200", null, "Sales tax TX", null);
        assertThat(lines(result)).containsExactly("1200 53300.00", "4000 (40000.00)", "4100 (10000.00)",
            "2200 (3300.00)");
        assertThat(result.totalUsd()).isEqualByComparingTo("53300.00");
    }

    @Test
    void aCreditMemoGoesTheOtherWay() {
        InvoicePosting.Result result = InvoicePosting.lines(true, List.of(
            new InvoicePosting.Line(new BigDecimal("2000.00"), "4900", null, null)),
            new BigDecimal("165.00"), BigDecimal.ONE, "1200", "2200", null, null, null);
        assertThat(lines(result)).containsExactly("1200 (2165.00)", "4900 2000.00", "2200 165.00");
    }

    @Test
    void foreignAmountsConvertPerAccountAndTheReceivableTakesTheirSum() {
        InvoicePosting.Result result = InvoicePosting.lines(false, List.of(
            new InvoicePosting.Line(new BigDecimal("0.10"), "4000", null, null),
            new InvoicePosting.Line(new BigDecimal("0.10"), "4000", null, null),
            new InvoicePosting.Line(new BigDecimal("0.10"), "4100", null, null)),
            BigDecimal.ZERO, new BigDecimal("1.0850"), "1200", "2200", null, null, null);
        // 0.20 × 1.085 = 0.217 → 0.22, 0.10 × 1.085 = 0.1085 → 0.11: the receivable is 0.33, not 0.30 × 1.085.
        assertThat(lines(result)).containsExactly("1200 0.33", "4000 (0.22)", "4100 (0.11)");
    }

    @Property
    void theEntryAlwaysBalances(@ForAll @Size(min = 1, max = 8)
        List<@BigRange(min = "0", max = "100000") @Scale(2) BigDecimal> amounts,
        @ForAll @BigRange(min = "0", max = "10000") @Scale(2) BigDecimal tax,
        @ForAll @BigRange(min = "0.5", max = "2") @Scale(4) BigDecimal rate, @ForAll boolean creditMemo) {
        List<InvoicePosting.Line> lines = new ArrayList<>();
        for (int i = 0; i < amounts.size(); i++) {
            lines.add(new InvoicePosting.Line(amounts.get(i), i % 2 == 0 ? "4000" : "4100", null, null));
        }
        InvoicePosting.Result result = InvoicePosting.lines(creditMemo, lines, tax, rate, "1200", "2200", null, null,
            null);
        assertThat(JournalValidator.totals(result.lines()).balanced()).isTrue();
        assertThat(result.lines().stream().allMatch(l -> (l.debit() == null) != (l.credit() == null))).isTrue();
    }
}
