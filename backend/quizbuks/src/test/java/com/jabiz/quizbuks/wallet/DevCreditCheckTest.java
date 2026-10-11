package com.jabiz.quizbuks.wallet;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DevCreditCheckTest {

    @Test
    void refusesTheDevelopmentCreditNextToALiveStripeKey() {
        assertThat(DevCreditCheck.problems(true, "sk_live_abc")).singleElement()
            .satisfies(problem -> assertThat(problem.toString()).contains(DevCredit.ENABLED));
        assertThat(DevCreditCheck.problems(true, "sk_test_abc")).isEmpty();
        assertThat(DevCreditCheck.problems(true, "")).isEmpty();
        assertThat(DevCreditCheck.problems(false, "sk_live_abc")).isEmpty();
    }

    @Test
    void lockNamesKeepTheKindsApart() {
        assertThat(QbWallets.lockName(QbWallets.SPONSOR, "u1")).isEqualTo("qb.wallet:SPONSOR:u1");
        assertThat(QbWallets.lockName(QbWallets.TAKER, "u1")).isNotEqualTo(QbWallets.lockName(QbWallets.SPONSOR, "u1"));
        assertThat(QbWallets.lockName(QbWallets.SPONSOR, null)).isNull();
    }
}
