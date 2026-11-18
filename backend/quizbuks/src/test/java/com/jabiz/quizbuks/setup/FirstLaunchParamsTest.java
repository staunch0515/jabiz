package com.jabiz.quizbuks.setup;

import com.jabiz.quizbuks.setup.FirstLaunchParams.Verdict;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The parameters a first launch's configuration may set (F-LAUNCH, docs/quizbuks/plans/impl/F-LAUNCH.md section 4, H4):
 * only the listed keys; a money switch keeps the first launch's value of {@link FirstLaunchSwitches}; an amount never.
 */
class FirstLaunchParamsTest {

    private static final Map<String, QbParams.Param> PARAMS = new HashMap<>();

    static {
        QbParams.ALL.forEach(param -> PARAMS.put(param.key(), param));
    }

    @Test
    void theListedKeysAreSettableParametersThatAreNeitherMoneySwitchesNorAmounts() {
        assertThat(FirstLaunchParams.SETTABLE).isNotEmpty().doesNotHaveDuplicates()
            .contains(QbParams.AI_SPONSOR_DAILY_JOBS, QbParams.REVIEW_SPONSOR_AUTO);
        for (String key : FirstLaunchParams.SETTABLE) {
            assertThat(PARAMS).as("%s is a parameter of QbParams.ALL", key).containsKey(key);
            assertThat(FirstLaunchSwitches.row(key)).as("%s is not in the money switches' registry", key).isEmpty();
            assertThat(FirstLaunchParams.amount(PARAMS.get(key))).as("%s is not an amount", key).isFalse();
            assertThat(FirstLaunchParams.verdict(key)).as(key).isEqualTo(Verdict.SETTABLE);
        }
    }

    @Test
    void aMoneySwitchIsFixedAtTheFirstLaunchsValue() {
        assertThat(FirstLaunchParams.verdict(QbParams.KUDOS_BUY_ENABLED)).isEqualTo(Verdict.FIXED);
        assertThat(FirstLaunchParams.verdict(QbParams.REWARD_KUDOS_ENABLED)).isEqualTo(Verdict.FIXED);
        assertThat(FirstLaunchParams.verdict(QbParams.AI_ENABLED)).isEqualTo(Verdict.FIXED);
        assertThat(FirstLaunchParams.fixedValue(QbParams.KUDOS_BUY_ENABLED)).contains(false);
        assertThat(FirstLaunchParams.fixedValue(QbParams.REWARD_KUDOS_ENABLED)).contains(false);
        assertThat(FirstLaunchParams.fixedValue(QbParams.REVIEW_SPONSOR_AUTO)).isEmpty();
        // Every row of the registry with a first launch's value is fixed (not settable), amounts are refused as such.
        for (FirstLaunchSwitches.Row row : FirstLaunchSwitches.ALL) {
            Verdict verdict = FirstLaunchParams.verdict(row.key());
            assertThat(verdict).as(row.key()).isIn(Verdict.FIXED, Verdict.AMOUNT, Verdict.NOT_SETTABLE);
        }
    }

    @Test
    void anAmountIsNeverSettable() {
        assertThat(FirstLaunchParams.verdict(QbParams.TOPUP_MIN)).isEqualTo(Verdict.AMOUNT);
        assertThat(FirstLaunchParams.verdict(QbParams.TRANSFER_FEE)).isEqualTo(Verdict.AMOUNT);
        assertThat(FirstLaunchParams.verdict(QbParams.CREATOR_FEE_RATE)).isEqualTo(Verdict.AMOUNT);
        assertThat(FirstLaunchParams.verdict(QbParams.FEE_CREDIT_GRANT_BASIC)).isEqualTo(Verdict.AMOUNT);
        for (QbParams.Param param : QbParams.ALL) {
            if (FirstLaunchParams.amount(param)) {
                assertThat(FirstLaunchParams.verdict(param.key())).as(param.key()).isEqualTo(Verdict.AMOUNT);
            }
        }
    }

    @Test
    void anythingElseIsNotSettable() {
        assertThat(FirstLaunchParams.verdict(QbParams.APP_MIN_VERSION.replace("app", "nope"))).isEqualTo(
            Verdict.NOT_SETTABLE);
        assertThat(FirstLaunchParams.verdict(QbParams.PROSPECT_ENABLED)).isEqualTo(Verdict.NOT_SETTABLE);
        assertThat(FirstLaunchParams.verdict(null)).isEqualTo(Verdict.NOT_SETTABLE);
    }
}
