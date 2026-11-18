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
            // A money switch's row is settable only with a rule on its value (the reward methods: offline only).
            if (FirstLaunchSwitches.row(key).isPresent()) {
                assertThat(FirstLaunchParams.VALUE_RULES).as("%s is in the registry, so its values are ruled", key)
                    .containsKey(key);
            }
            assertThat(FirstLaunchParams.amount(PARAMS.get(key))).as("%s is not an amount", key).isFalse();
            assertThat(FirstLaunchParams.verdict(key)).as(key).isEqualTo(Verdict.SETTABLE);
        }
    }

    /** What the requester must fill in at the first launch that is neither an amount nor a money switch (S7, #733). */
    @Test
    void theIssuerTheJournalMappingsTheAnnouncementLimitAndTheRewardMethodsAreSettable() {
        assertThat(FirstLaunchParams.SETTABLE).containsAll(QbParams.ISSUER).containsAll(QbParams.JOURNAL_ACCOUNTS)
            .contains(QbParams.LEGAL_MAX_ANNOUNCE_DAYS, QbParams.REWARD_DEFAULT_METHODS);
        assertThat(QbParams.JOURNAL_ACCOUNTS).hasSize(18);
        assertThat(FirstLaunchParams.verdict(QbParams.ISSUER_REGISTRATION_NO)).isEqualTo(Verdict.SETTABLE);
        assertThat(FirstLaunchParams.verdict(QbParams.JOURNAL_ACCOUNT_2160)).isEqualTo(Verdict.SETTABLE);
        assertThat(FirstLaunchParams.verdict(QbParams.LEGAL_MAX_ANNOUNCE_DAYS)).isEqualTo(Verdict.SETTABLE);
        assertThat(FirstLaunchParams.verdict(QbParams.REWARD_DEFAULT_METHODS)).isEqualTo(Verdict.SETTABLE);
        assertThat(FirstLaunchParams.fixedValue(QbParams.REWARD_DEFAULT_METHODS)).isEmpty();
    }

    @Test
    void theRewardMethodsMayOnlyBeNoneOrOfflineRedemption() {
        String key = QbParams.REWARD_DEFAULT_METHODS;
        assertThat(FirstLaunchParams.verdict(key, "")).isEqualTo(Verdict.SETTABLE);
        assertThat(FirstLaunchParams.verdict(key, null)).isEqualTo(Verdict.SETTABLE);
        assertThat(FirstLaunchParams.verdict(key, " OFFLINE_REDEEM ")).isEqualTo(Verdict.SETTABLE);
        assertThat(FirstLaunchParams.verdict(key, "PLATFORM")).isEqualTo(Verdict.VALUE_REFUSED);
        assertThat(FirstLaunchParams.verdict(key, "OFFLINE_REDEEM,PLATFORM")).isEqualTo(Verdict.VALUE_REFUSED);
        assertThat(FirstLaunchParams.verdict(key, "DIGITAL_PRIZE")).isEqualTo(Verdict.VALUE_REFUSED);
        assertThat(FirstLaunchParams.verdict(key, "SPONSOR_CASH")).isEqualTo(Verdict.VALUE_REFUSED);
        // A key without a rule on its value takes any value (its kind is checked as for every parameter).
        assertThat(FirstLaunchParams.verdict(QbParams.ISSUER_NAME, "株式会社サンプル")).isEqualTo(Verdict.SETTABLE);
        // The value never makes a key settable that is not.
        assertThat(FirstLaunchParams.verdict(QbParams.TOPUP_MIN, "1000")).isEqualTo(Verdict.AMOUNT);
        assertThat(FirstLaunchParams.verdict(QbParams.KUDOS_BUY_ENABLED, "false")).isEqualTo(Verdict.FIXED);
    }

    @Test
    void aMoneySwitchIsFixedAtTheFirstLaunchsValue() {
        assertThat(FirstLaunchParams.verdict(QbParams.KUDOS_BUY_ENABLED)).isEqualTo(Verdict.FIXED);
        assertThat(FirstLaunchParams.verdict(QbParams.REWARD_KUDOS_ENABLED)).isEqualTo(Verdict.FIXED);
        assertThat(FirstLaunchParams.verdict(QbParams.AI_ENABLED)).isEqualTo(Verdict.FIXED);
        assertThat(FirstLaunchParams.fixedValue(QbParams.KUDOS_BUY_ENABLED)).contains(false);
        assertThat(FirstLaunchParams.fixedValue(QbParams.REWARD_KUDOS_ENABLED)).contains(false);
        assertThat(FirstLaunchParams.fixedValue(QbParams.REVIEW_SPONSOR_AUTO)).isEmpty();
        // Every row of the registry is fixed or refused, unless its values are ruled.
        for (FirstLaunchSwitches.Row row : FirstLaunchSwitches.ALL) {
            Verdict verdict = FirstLaunchParams.verdict(row.key());
            if (FirstLaunchParams.VALUE_RULES.containsKey(row.key())) {
                // Settable, but only with the values its rule allows (the reward methods: offline only).
                assertThat(verdict).as(row.key()).isEqualTo(Verdict.SETTABLE);
            } else {
                assertThat(verdict).as(row.key()).isIn(Verdict.FIXED, Verdict.AMOUNT, Verdict.NOT_SETTABLE);
            }
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
