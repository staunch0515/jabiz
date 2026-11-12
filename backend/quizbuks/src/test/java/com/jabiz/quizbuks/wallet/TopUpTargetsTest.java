package com.jabiz.quizbuks.wallet;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Where a top-up goes (Q87a, D-Q87-2): the switches decide, the code only how a target is credited. */
class TopUpTargetsTest {

    @Test
    void theParameterListsTheKnownTargetsOnce() {
        assertThat(TopUpTargets.parse("KUDOS,CASH")).containsExactly("KUDOS", "CASH");
        assertThat(TopUpTargets.parse(" cash , kudos,CASH,GOLD,")).containsExactly("CASH", "KUDOS");
        assertThat(TopUpTargets.parse("")).isEmpty();
        assertThat(TopUpTargets.parse(null)).isEmpty();
    }

    @Test
    void theChoiceMustBeOfferedAndNoneTakesTheDefault() {
        assertThat(TopUpTargets.choose(null, "KUDOS,CASH", "KUDOS")).isEqualTo("KUDOS");
        assertThat(TopUpTargets.choose(null, "KUDOS,CASH", "cash")).isEqualTo("CASH");
        assertThat(TopUpTargets.choose("CASH", "KUDOS,CASH", "KUDOS")).isEqualTo("CASH");
        assertThat(TopUpTargets.choose("CASH", "KUDOS", "KUDOS")).isNull();
        assertThat(TopUpTargets.problem("CASH", "KUDOS").ruleCode()).isEqualTo(WalletCodes.TOPUP_TARGET);
        // A default that is not offered is no target: the switches contradict each other.
        assertThat(TopUpTargets.choose(null, "CASH", "KUDOS")).isNull();
        assertThat(TopUpTargets.problem(null, "CASH").ruleCode()).isEqualTo(WalletCodes.TOPUP_UNAVAILABLE);
        assertThat(TopUpTargets.problem("CASH", "").ruleCode()).isEqualTo(WalletCodes.TOPUP_UNAVAILABLE);
    }

    @Test
    void theFirstLaunchTopsUpIntoCashOnlyAndAnUnchosenTargetGoesThere() {
        // F-F10a: targets CASH with the default CASH (FirstLaunchSwitches): nothing chosen is cash; Kudos is refused.
        assertThat(TopUpTargets.choose(null, "CASH", "CASH")).isEqualTo("CASH");
        assertThat(TopUpTargets.choose("CASH", "CASH", "CASH")).isEqualTo("CASH");
        assertThat(TopUpTargets.choose("KUDOS", "CASH", "CASH")).isNull();
        assertThat(TopUpTargets.problem("KUDOS", "CASH").ruleCode()).isEqualTo(WalletCodes.TOPUP_TARGET);
    }

    @Test
    void aTargetCreditsItsWalletAndAccount() {
        assertThat(TopUpTargets.walletKind(TopUpTargets.CASH)).isEqualTo(QbWallets.CASH);
        assertThat(TopUpTargets.account(TopUpTargets.CASH)).isEqualTo(QbLedger.SPONSOR_CASH);
        assertThat(TopUpTargets.walletKind(TopUpTargets.KUDOS)).isEqualTo(QbWallets.SPONSOR);
        assertThat(TopUpTargets.account(TopUpTargets.KUDOS)).isEqualTo(QbLedger.SPONSOR_PREPAYMENTS);
        assertThat(TopUpTargets.paysRewards(TopUpTargets.KUDOS)).isTrue();
        assertThat(TopUpTargets.paysRewards(TopUpTargets.CASH)).isFalse();
        // A record of before Q87a: Kudos.
        assertThat(TopUpTargets.of(null)).isEqualTo(TopUpTargets.KUDOS);
    }
}
