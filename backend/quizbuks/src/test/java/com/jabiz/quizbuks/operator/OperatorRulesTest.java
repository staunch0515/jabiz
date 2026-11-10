package com.jabiz.quizbuks.operator;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class OperatorRulesTest {

    @Test
    void anOperatorMovesWholeYenUpToItsBalanceToItsDirectSponsor() {
        BigDecimal balance = BigDecimal.valueOf(5000);
        assertThat(OperatorRules.transferable(BigDecimal.valueOf(5000), balance)).isTrue();
        assertThat(OperatorRules.transferable(BigDecimal.ONE, balance)).isTrue();
        assertThat(OperatorRules.transferable(new BigDecimal("3000.00"), balance)).isTrue();
        assertThat(OperatorRules.transferable(BigDecimal.valueOf(5001), balance)).isFalse();
        assertThat(OperatorRules.transferable(BigDecimal.ZERO, balance)).isFalse();
        assertThat(OperatorRules.transferable(new BigDecimal("10.5"), balance)).isFalse();
        assertThat(OperatorRules.transferable(null, balance)).isFalse();
        assertThat(OperatorRules.transferable(BigDecimal.ONE, null)).isFalse();
        // A balance below nothing (withdrawals asked for before a correction) lets nothing go.
        assertThat(OperatorRules.transferable(BigDecimal.ONE, BigDecimal.valueOf(-1))).isFalse();
    }
}
