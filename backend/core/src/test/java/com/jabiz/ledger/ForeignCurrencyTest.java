package com.jabiz.ledger;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.jabiz.ledger.Direction.CREDIT;
import static com.jabiz.ledger.Direction.DEBIT;
import static org.assertj.core.api.Assertions.assertThat;

/** Entries in foreign currencies (docs/design/11-ledger-events-jobs.md section 1.8; decision D24 item 5). */
class ForeignCurrencyTest {

    static PostingLine fx(String account, Direction side, String functional, String currency, String amount,
        String rate) {
        return new PostingLine(account, side, new BigDecimal(functional), null, Map.of(),
            new ForeignAmount(currency, new BigDecimal(amount), new BigDecimal(rate)));
    }

    static List<String> codes(List<Violation> violations) {
        return violations.stream().map(Violation::ruleCode).toList();
    }

    @Test
    void aForeignInvoiceBalancesInBothCurrencies() {
        List<PostingLine> invoice = List.of(
            fx("1200", DEBIT, "110.00", "EUR", "100.00", "1.1"),
            fx("4000", CREDIT, "110.00", "EUR", "100", "1.10"));
        assertThat(LedgerPosting.validate(invoice, 2, List.of(), "USD")).isEmpty();
        assertThat(LedgerPosting.validate(LedgerPosting.reversal(invoice), 2, List.of(), "USD")).isEmpty();
        assertThat(LedgerPosting.total(invoice, "EUR", DEBIT)).isEqualByComparingTo("100");
    }

    @Test
    void aSettlementPostsTheExchangeDifferenceInTheLedgerCurrency() {
        List<PostingLine> receipt = List.of(
            fx("1010", DEBIT, "120.00", "EUR", "100.00", "1.2"),
            fx("1200", CREDIT, "110.00", "EUR", "100.00", "1.1"),
            new PostingLine("7100", CREDIT, new BigDecimal("10.00")));
        assertThat(LedgerPosting.validate(receipt, 2, List.of(), "USD")).isEmpty();
    }

    @Test
    void theConvertedAmountIsRoundedHalfAwayFromZeroAndMustBeExact() {
        assertThat(new ForeignAmount("EUR", new BigDecimal("10.05"), new BigDecimal("1.1")).converted(2))
            .isEqualByComparingTo("11.06");
        assertThat(new ForeignAmount("JPY", new BigDecimal("1005"), new BigDecimal("0.00665")).converted(2))
            .isEqualByComparingTo("6.68");
        List<Violation> violations = LedgerPosting.validate(List.of(
            fx("1200", DEBIT, "11.05", "EUR", "10.05", "1.1"),
            fx("4000", CREDIT, "11.05", "EUR", "10.05", "1.1")), 2, List.of(), "USD");
        assertThat(codes(violations)).containsExactly(PlatformErrorCodes.LEDGER_FX_AMOUNT_MISMATCH,
            PlatformErrorCodes.LEDGER_FX_AMOUNT_MISMATCH);
        assertThat(violations.getFirst().params()).containsEntry("expected", "11.06");
    }

    @Test
    void everyForeignCurrencyBalancesOnItsOwn() {
        // Balanced in dollars (90 GBP at 1.2222222222 is 110.00), but 100 EUR against 90 GBP.
        List<Violation> violations = LedgerPosting.validate(List.of(
            fx("1200", DEBIT, "110.00", "EUR", "100", "1.1"),
            fx("2000", CREDIT, "110.00", "GBP", "90", "1.2222222222")), 2, List.of(), "USD");
        assertThat(codes(violations)).containsExactly(PlatformErrorCodes.LEDGER_UNBALANCED_IN_CURRENCY,
            PlatformErrorCodes.LEDGER_UNBALANCED_IN_CURRENCY);
        assertThat(violations.getFirst().params()).containsEntry("currency", "EUR").containsEntry("difference", "100");

        List<Violation> unbalanced = LedgerPosting.validate(List.of(
            new PostingLine("1", DEBIT, new BigDecimal("25000.00")),
            new PostingLine("2", CREDIT, new BigDecimal("24999.99"))), 2, List.of(), "USD");
        assertThat(unbalanced.getFirst().params()).containsEntry("difference", "0.01");
    }

    @Test
    void currenciesAmountsAndRatesAreChecked() {
        List<Violation> violations = LedgerPosting.validate(List.of(
            fx("1", DEBIT, "1", "USD", "1", "1"),
            fx("2", DEBIT, "1", "XYZ", "1", "1"),
            fx("3", DEBIT, "1", "EUR", "0", "1"),
            fx("4", DEBIT, "1", "JPY", "1.5", "1"),
            fx("5", DEBIT, "1", "EUR", "1", "0"),
            fx("6", DEBIT, "1", "EUR", "1", "0.00000000001"),
            new PostingLine("9", CREDIT, new BigDecimal("6"))), 2, List.of(), "USD");
        assertThat(codes(violations)).containsExactly(PlatformErrorCodes.LEDGER_CURRENCY_INVALID,
            PlatformErrorCodes.LEDGER_CURRENCY_INVALID, PlatformErrorCodes.LEDGER_AMOUNT_NOT_POSITIVE,
            PlatformErrorCodes.LEDGER_AMOUNT_SCALE, PlatformErrorCodes.LEDGER_RATE_INVALID,
            PlatformErrorCodes.LEDGER_RATE_INVALID);
        assertThat(ForeignAmount.scaleOf("XAU")).isEqualTo(ForeignAmount.DEFAULT_CURRENCY_SCALE);
        assertThat(ForeignAmount.scaleOf("KWD")).isEqualTo(3);
        assertThat(ForeignAmount.scaleOf(null)).isEqualTo(-1);
    }

    /** A posting built balanced in each currency: every foreign line has a counter line of the same currency. */
    @Provide
    Arbitrary<List<PostingLine>> balancedPostings() {
        Arbitrary<PostingLine[]> pair = Combinators.combine(
            Arbitraries.of("EUR", "GBP", "JPY"),
            Arbitraries.integers().between(1, 1_000_000),
            Arbitraries.integers().between(1, 5_000_000),
            Arbitraries.of(DEBIT, CREDIT)).as((currency, units, rateMillionths, side) -> {
                BigDecimal amount = BigDecimal.valueOf(units, ForeignAmount.scaleOf(currency));
                ForeignAmount foreign = new ForeignAmount(currency, amount, BigDecimal.valueOf(rateMillionths, 6));
                BigDecimal converted = foreign.converted(2);
                return new PostingLine[] {
                    new PostingLine("1100", side, converted, null, Map.of(), foreign),
                    new PostingLine("2100", side.opposite(), converted, null, Map.of(), foreign)};
            // An amount that converts to less than a cent cannot be booked (amounts are positive).
            }).filter(p -> p[0].amount().signum() > 0);
        return pair.list().ofMinSize(1).ofMaxSize(5).map(pairs -> {
            List<PostingLine> lines = new ArrayList<>();
            pairs.forEach(p -> lines.addAll(List.of(p)));
            return lines;
        });
    }

    @Property(tries = 300)
    void balancedForeignPostingsAndTheirReversalsAreAcceptedAndCancelOut(
        @ForAll("balancedPostings") List<PostingLine> lines) {
        assertThat(LedgerPosting.validate(lines, 2, List.of(), "USD")).isEmpty();
        List<PostingLine> reversal = LedgerPosting.reversal(lines);
        assertThat(LedgerPosting.validate(reversal, 2, List.of(), "USD")).isEmpty();
        List<PostingLine> both = new ArrayList<>(lines);
        both.addAll(reversal);
        for (String currency : List.of("EUR", "GBP", "JPY")) {
            assertThat(LedgerPosting.total(both, currency, DEBIT))
                .isEqualByComparingTo(LedgerPosting.total(both, currency, CREDIT));
        }
        assertThat(new LedgerBalances().post(both).total()).isZero();
    }
}
