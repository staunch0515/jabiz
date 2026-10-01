package com.jabiz.finance.calc;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static com.jabiz.finance.calc.PeriodPolicy.Source.AR;
import static com.jabiz.finance.calc.PeriodPolicy.Source.GL;
import static com.jabiz.finance.calc.PeriodPolicy.Status.CLOSED;
import static com.jabiz.finance.calc.PeriodPolicy.Status.OPEN;
import static com.jabiz.finance.calc.PeriodPolicy.Status.SOFT_CLOSED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** FIN-PC-003 acceptance 1–3. */
class PeriodPolicyTest {

    private static Optional<String> check(PeriodPolicy.Status status, boolean subledgerClosed,
        PeriodPolicy.Source source, boolean adjusting, boolean mayClose) {
        return PeriodPolicy.check(new PeriodPolicy.State("2026-01", status, subledgerClosed), source, adjusting,
            mayClose).map(PeriodPolicy.Refusal::code);
    }

    @Test
    void anOpenPeriodTakesEverything() {
        assertThat(check(OPEN, false, GL, false, false)).isEmpty();
        assertThat(check(OPEN, false, AR, false, false)).isEmpty();
    }

    @Test
    void aClosedPeriodTakesNothing() {
        assertThat(check(CLOSED, false, GL, true, true)).contains(PeriodPolicy.PERIOD_CLOSED);
        assertThat(PeriodPolicy.check(new PeriodPolicy.State("2026-01", CLOSED, false), GL, false, false)
            .orElseThrow().message()).isEqualTo("Period 2026-01 is closed");
    }

    @Test
    void aSoftClosedPeriodTakesOnlyAdjustingEntriesOfTheClosePermission() {
        assertThat(check(SOFT_CLOSED, false, GL, false, false)).contains(PeriodPolicy.PERIOD_SOFT_CLOSED);
        assertThat(check(SOFT_CLOSED, false, GL, true, false)).contains(PeriodPolicy.PERIOD_SOFT_CLOSED);
        assertThat(check(SOFT_CLOSED, false, GL, false, true)).contains(PeriodPolicy.PERIOD_SOFT_CLOSED);
        assertThat(check(SOFT_CLOSED, false, GL, true, true)).isEmpty();
    }

    @Test
    void aClosedSubledgerRefusesItsDocumentsWhileTheLedgerIsOpen() {
        assertThat(check(OPEN, true, AR, false, true)).contains(PeriodPolicy.SUBLEDGER_CLOSED);
        // A journal entry is no subledger document.
        assertThat(check(OPEN, true, GL, false, false)).isEmpty();
        assertThatThrownBy(() -> new PeriodPolicy.State("2026-01", null, false))
            .isInstanceOf(NullPointerException.class);
    }
}
