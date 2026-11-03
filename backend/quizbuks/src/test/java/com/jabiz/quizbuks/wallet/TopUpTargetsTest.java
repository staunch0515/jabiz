package com.jabiz.quizbuks.wallet;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Q87 (D-Q87-2): what a top-up becomes, within what the administrators allow. */
class TopUpTargetsTest {

    @Test
    void theTargetGivenMustBeAllowedAndTheDefaultIsTakenOtherwise() {
        assertThat(TopUpTargets.allowed("KUDOS,CASH")).containsExactly("KUDOS", "CASH");
        assertThat(TopUpTargets.allowed(" CASH , NOPE, CASH")).containsExactly("CASH");
        assertThat(TopUpTargets.allowed(null)).isEmpty();
        assertThat(TopUpTargets.resolve("CASH", "KUDOS,CASH", "KUDOS")).isEqualTo("CASH");
        assertThat(TopUpTargets.resolve(null, "KUDOS,CASH", "KUDOS")).isEqualTo("KUDOS");
        assertThat(TopUpTargets.resolve("", "KUDOS,CASH", "CASH")).isEqualTo("CASH");
        // The default is not allowed: the first allowed one.
        assertThat(TopUpTargets.resolve(null, "CASH", "KUDOS")).isEqualTo("CASH");
        assertThat(TopUpTargets.resolve("CASH", "KUDOS", "KUDOS")).isNull();
        assertThat(TopUpTargets.resolve(null, "", "KUDOS")).isNull();
        assertThat(TopUpTargets.walletKind("CASH")).isEqualTo(QbWallets.CASH);
        assertThat(TopUpTargets.walletKind("KUDOS")).isEqualTo(QbWallets.SPONSOR);
        assertThat(TopUpTargets.account("CASH")).isEqualTo(QbLedger.SPONSOR_CASH);
        assertThat(TopUpTargets.account(null)).isEqualTo(QbLedger.SPONSOR_PREPAYMENTS);
        // Stored before Q87: Kudos.
        assertThat(TopUpTargets.of(null)).isEqualTo(QbTopUps.KUDOS);
        assertThat(TopUpTargets.of("CASH")).isEqualTo(QbTopUps.CASH);
    }
}
