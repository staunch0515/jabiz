package com.jabiz.ledger;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static com.jabiz.ledger.Direction.CREDIT;
import static com.jabiz.ledger.Direction.DEBIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerPostingTest {

    private static PostingLine line(String account, Direction direction, String amount) {
        return new PostingLine(account, direction, new BigDecimal(amount));
    }

    @Test
    void aBalancedTransactionIsAccepted() {
        List<PostingLine> lines = List.of(line("1100", DEBIT, "1000"), line("4100", CREDIT, "900"),
            line("2100", CREDIT, "100"));

        assertThat(LedgerPosting.validate(lines, 0)).isEmpty();
        assertThat(LedgerPosting.total(lines, DEBIT)).isEqualByComparingTo("1000");
        assertThat(LedgerPosting.total(lines, CREDIT)).isEqualByComparingTo("1000");
    }

    @Test
    void everyBrokenRuleIsReportedAtOnce() {
        List<Violation> violations = LedgerPosting.validate(List.of(line("1100", DEBIT, "-5")), 0);

        assertThat(violations).extracting(Violation::ruleCode).containsExactly(
            PlatformErrorCodes.LEDGER_TOO_FEW_LINES, PlatformErrorCodes.LEDGER_AMOUNT_NOT_POSITIVE,
            PlatformErrorCodes.LEDGER_UNBALANCED);
        assertThat(violations.get(1).params()).containsEntry("line", 1);
        assertThat(violations.get(2).params()).containsEntry("debit", "-5").containsEntry("credit", "0");
    }

    @Test
    void unbalancedTransactionsAreRefused() {
        assertThat(LedgerPosting.validate(List.of(line("1100", DEBIT, "1000"), line("4100", CREDIT, "999")), 0))
            .extracting(Violation::ruleCode).containsExactly(PlatformErrorCodes.LEDGER_UNBALANCED);
    }

    @Test
    void amountsKeepToTheScaleOfTheLedger() {
        List<PostingLine> lines = List.of(line("1100", DEBIT, "10.5"), line("4100", CREDIT, "10.50"));

        assertThat(LedgerPosting.validate(lines, 2)).isEmpty();
        assertThat(LedgerPosting.validate(lines, 0)).extracting(Violation::ruleCode).containsExactly(
            PlatformErrorCodes.LEDGER_AMOUNT_SCALE, PlatformErrorCodes.LEDGER_AMOUNT_SCALE);
        // Trailing zeros are no extra precision.
        assertThat(LedgerPosting.validate(List.of(line("1100", DEBIT, "10.00"), line("4100", CREDIT, "10")), 0))
            .isEmpty();
    }

    @Test
    void zeroAmountsAreRefused() {
        assertThat(LedgerPosting.validate(List.of(line("1100", DEBIT, "0"), line("4100", CREDIT, "0")), 0))
            .extracting(Violation::ruleCode).containsExactly(
                PlatformErrorCodes.LEDGER_AMOUNT_NOT_POSITIVE, PlatformErrorCodes.LEDGER_AMOUNT_NOT_POSITIVE);
    }

    @Test
    void aTransactionHasAtMostMaxLines() {
        List<PostingLine> lines = new java.util.ArrayList<>(Collections.nCopies(LedgerPosting.MAX_LINES,
            line("1100", DEBIT, "1")));
        lines.add(line("4100", CREDIT, String.valueOf(LedgerPosting.MAX_LINES)));

        assertThat(LedgerPosting.validate(lines, 0)).extracting(Violation::ruleCode)
            .containsExactly(PlatformErrorCodes.LEDGER_TOO_MANY_LINES);
    }

    @Test
    void aReversalSwapsTheSides() {
        List<PostingLine> lines = List.of(line("1100", DEBIT, "1000"), line("4100", CREDIT, "1000"));

        assertThat(LedgerPosting.reversal(lines)).containsExactly(
            line("1100", CREDIT, "1000"), line("4100", DEBIT, "1000"));
        assertThat(DEBIT.opposite()).isEqualTo(CREDIT);
        assertThat(CREDIT.opposite()).isEqualTo(DEBIT);
    }

    @Test
    void linesNeedAllTheirParts() {
        assertThatThrownBy(() -> new PostingLine(null, DEBIT, BigDecimal.ONE)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PostingLine("1", null, BigDecimal.ONE)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PostingLine("1", DEBIT, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void balancesAddUpPerAccount() {
        LedgerBalances balances = new LedgerBalances()
            .post(List.of(line("1100", DEBIT, "1000"), line("4100", CREDIT, "1000")))
            .post(List.of(line("1100", CREDIT, "300"), line("1000", DEBIT, "300")));

        assertThat(balances.of("1100").debit()).isEqualByComparingTo("1000");
        assertThat(balances.of("1100").credit()).isEqualByComparingTo("300");
        assertThat(balances.of("1100").balance()).isEqualByComparingTo("700");
        assertThat(balances.of("4100").balance()).isEqualByComparingTo("-1000");
        assertThat(balances.of("9999")).isEqualTo(LedgerBalances.Balance.ZERO);
        assertThat(balances.all()).containsOnlyKeys("1000", "1100", "4100");
        assertThat(balances.total()).isEqualByComparingTo("0");
    }
}
