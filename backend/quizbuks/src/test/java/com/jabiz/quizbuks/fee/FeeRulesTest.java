package com.jabiz.quizbuks.fee;

import com.jabiz.entity.Violation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The platform fees' rules (Q87b): tax rounded once per invoice and rate, settlement, what unpaid fees stop. */
class FeeRulesTest {

    static final BigDecimal TEN = new BigDecimal("0.10");

    static BigDecimal d(long value) {
        return BigDecimal.valueOf(value);
    }

    static FeeRules.Line line(long amount, boolean receivable) {
        return new FeeRules.Line(d(amount), TEN, FeeRules.provisionalTax(d(amount), TEN), receivable);
    }

    static List<String> codes(List<Violation> problems) {
        return problems.stream().map(Violation::ruleCode).toList();
    }

    @Test
    void aChargesTaxIsProvisionalAndTheInvoiceRoundsOncePerRate() {
        assertThat(FeeRules.provisionalTax(d(105), TEN)).isEqualByComparingTo("10");
        assertThat(FeeRules.provisionalTax(d(-105), TEN)).isEqualByComparingTo("-10");
        // 105 + 105 = 210: 21 of tax on the invoice, 20 provisionally: one yen more, owed with the rest.
        FeeRules.Invoice owed = FeeRules.invoice(List.of(line(105, true), line(105, true)), d(0));
        assertThat(owed.amount()).isEqualByComparingTo("210");
        assertThat(owed.tax()).isEqualByComparingTo("21");
        assertThat(owed.total()).isEqualByComparingTo("231");
        assertThat(owed.adjustment()).isEqualByComparingTo("1");
        assertThat(owed.adjustDue()).isTrue();
        assertThat(owed.due()).isEqualByComparingTo("231");
        assertThat(owed.paid()).isEqualByComparingTo("0");
        assertThat(owed.rates()).singleElement().satisfies(rate -> assertThat(rate.tax()).isEqualByComparingTo("21"));
    }

    @Test
    void aPaidInvoicesAdjustmentComesFromTheCashAccountWhenItCanElseItIsDue() {
        List<FeeRules.Line> paid = List.of(line(105, false), line(105, false));
        FeeRules.Invoice fromCash = FeeRules.invoice(paid, d(5));
        assertThat(fromCash.adjustDue()).isFalse();
        assertThat(fromCash.paid()).isEqualByComparingTo("231");
        assertThat(fromCash.due()).isEqualByComparingTo("0");
        FeeRules.Invoice noCash = FeeRules.invoice(paid, d(0));
        assertThat(noCash.adjustDue()).isTrue();
        assertThat(noCash.due()).isEqualByComparingTo("1");
        // No difference: nothing to post.
        FeeRules.Invoice exact = FeeRules.invoice(List.of(line(1000, false)), d(0));
        assertThat(exact.adjustment()).isZero();
        assertThat(exact.total()).isEqualByComparingTo("1100");
        assertThat(exact.due()).isZero();
    }

    @Test
    void whatUnpaidFeesStop() {
        BigDecimal credit = d(10_000);
        assertThat(FeeRules.refusals(new FeeRules.Standing(FeeRules.PREPAID, null, d(0), false), true)).isEmpty();
        assertThat(codes(FeeRules.refusals(new FeeRules.Standing(FeeRules.PREPAID, null, d(1), false), true)))
            .containsExactly(FeeCodes.UNPAID);
        assertThat(FeeRules.refusals(new FeeRules.Standing(FeeRules.POSTPAID, credit, d(10_000), false), true))
            .isEmpty();
        assertThat(codes(FeeRules.refusals(new FeeRules.Standing(FeeRules.POSTPAID, credit, d(10_001), false), true)))
            .containsExactly(FeeCodes.CREDIT_EXCEEDED);
        assertThat(codes(FeeRules.refusals(new FeeRules.Standing(FeeRules.POSTPAID, credit, d(1), true), true)))
            .containsExactly(FeeCodes.OVERDUE);
        // The overdue switch off: within the credit, nothing is stopped.
        assertThat(FeeRules.refusals(new FeeRules.Standing(FeeRules.POSTPAID, credit, d(1), true), false)).isEmpty();
    }

    @Test
    void howAChargeIsSettledAndWhenOneThatMustBeCoveredIsNot() {
        assertThat(FeeRules.paysFromCash(FeeRules.PREPAID, d(1100), d(1100))).isTrue();
        assertThat(FeeRules.paysFromCash(FeeRules.PREPAID, d(1099), d(1100))).isFalse();
        assertThat(FeeRules.paysFromCash(FeeRules.POSTPAID, d(99_999), d(1100))).isFalse();
        FeeRules.Standing prepaid = new FeeRules.Standing(FeeRules.PREPAID, null, d(0), false);
        assertThat(FeeRules.uncovered(prepaid, d(1100), d(1100))).isNull();
        assertThat(FeeRules.uncovered(prepaid, d(1099), d(1100)).ruleCode()).isEqualTo(FeeCodes.FUNDS);
        FeeRules.Standing postpaid = new FeeRules.Standing(FeeRules.POSTPAID, d(2000), d(1000), false);
        assertThat(FeeRules.uncovered(postpaid, d(0), d(1000))).isNull();
        assertThat(FeeRules.uncovered(postpaid, d(0), d(1001)).ruleCode()).isEqualTo(FeeCodes.CREDIT_EXCEEDED);
    }
}
