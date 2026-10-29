package com.jabiz.quizbuks.operator;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/** The arithmetic of the operators' settlements (Q48b, Q48d). */
class OperatorSettlementsTest {

    static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");

    @Test
    void theShareIsRoundedDownAndNothingOfANegativeMonth() {
        assertThat(OperatorSettlements.operatorShare(new BigDecimal("22"), new BigDecimal("0.2")))
            .isEqualByComparingTo("17");
        assertThat(OperatorSettlements.operatorShare(new BigDecimal("-5"), new BigDecimal("0.2")))
            .isEqualByComparingTo("0");
        assertThat(OperatorSettlements.operatorShare(new BigDecimal("100"), BigDecimal.ZERO))
            .isEqualByComparingTo("100");
    }

    @Test
    void costsAndWhatWasCarriedAreDeductedAndTheRestCarriedNeverBelowZero() {
        BigDecimal[] paid = OperatorSettlements.payable(new BigDecimal("100"), new BigDecimal("30"),
            new BigDecimal("20"));
        assertThat(paid[0]).isEqualByComparingTo("50");
        assertThat(paid[1]).isEqualByComparingTo("0");
        BigDecimal[] carried = OperatorSettlements.payable(new BigDecimal("10"), new BigDecimal("30"),
            new BigDecimal("5"));
        assertThat(carried[0]).isEqualByComparingTo("0");
        assertThat(carried[1]).isEqualByComparingTo("25");
    }

    @Test
    void monthsAreJapans() {
        assertThat(OperatorSettlements.monthStart("2026-11", TOKYO)).isEqualTo(Instant.parse("2026-10-31T15:00:00Z"));
        assertThat(OperatorSettlements.monthEnd("2026-12", TOKYO)).isEqualTo(Instant.parse("2026-12-31T15:00:00Z"));
        // The job's run on December 1st at 16:13 UTC is on the 2nd in Tokyo: it settles November.
        assertThat(OperatorSettlements.previousMonth(Instant.parse("2026-12-01T16:13:00Z"), TOKYO))
            .isEqualTo("2026-11");
        assertThat(OperatorSettlements.reference("0192f0a0-0000-7000", "2026-11")).isEqualTo("op_2026-11_0192f0a0");
    }
}
