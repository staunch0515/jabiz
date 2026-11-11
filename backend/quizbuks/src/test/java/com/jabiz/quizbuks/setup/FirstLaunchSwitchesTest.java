package com.jabiz.quizbuks.setup;

import com.jabiz.param.ParamKinds;
import com.jabiz.quizbuks.setup.FirstLaunchSwitches.Row;
import com.jabiz.quizbuks.setup.FirstLaunchSwitches.Status;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The registry of the first launch's switches (F-F10a, docs/quizbuks/plans/impl/F-F10.md section 2). The second test is
 * what makes the charge groups of F-M list themselves: a pull request that adds the key of a waiting row fails here
 * until the row is active.
 */
class FirstLaunchSwitchesTest {

    private static final Map<String, QbParams.Param> PARAMS = new java.util.HashMap<>();

    static {
        QbParams.ALL.forEach(param -> PARAMS.put(param.key(), param));
    }

    @Test
    void anActiveRowIsAParameterAndItsValueIsOneOfItsKind() {
        for (Row row : FirstLaunchSwitches.ALL) {
            if (row.status() != Status.ACTIVE) {
                continue;
            }
            QbParams.Param param = PARAMS.get(row.key());
            assertThat(param).as("%s is a parameter of QbParams.ALL", row.key()).isNotNull();
            if (row.value() == null) {
                continue;
            }
            // The platform's own check of a value: the kind accepts it (a numeric one writes it with its scale, as the
            // declared "0" of a rate is stored "0.0000").
            assertThat(ParamKinds.canonical(ParamKinds.parse(param.valueKind()), row.value())).as("%s", row.key())
                .isNotNull();
            assertThat(row.value().getClass()).as("%s is typed as its declared value", row.key())
                .isEqualTo(param.value().getClass());
            // What QB_SETUP proposes must be a controlled change, or the value would not be four-eyed like the rest.
            assertThat(QbParams.CONTROLLED).as("%s is controlled", row.key()).contains(row.key());
            // A value that is not the declared one says why.
            if (!String.valueOf(row.value()).equals(String.valueOf(param.value()))) {
                assertThat(row.why()).as("why %s differs from its declared value", row.key()).isNotBlank();
            }
        }
    }

    @Test
    void aWaitingRowWhoseKeyExistsIsAFeatureThatForgotItsRow() {
        Set<String> existing = new HashSet<>(PARAMS.keySet());
        for (Row row : FirstLaunchSwitches.ALL) {
            if (row.status() != Status.WAITING) {
                continue;
            }
            assertThat(row.waitingFor()).as("%s names its feature", row.key()).isNotBlank();
            if (existing.contains(row.key())) {
                assertThat(FirstLaunchSwitches.KEY_BEFORE_MEANING).as("%s: %s has added the key, so turn its row "
                    + "ACTIVE in FirstLaunchSwitches and write the first launch's value", row.key(), row.waitingFor())
                    .contains(row.key());
            }
        }
        // And the allowance itself must not outlive its row.
        for (String key : FirstLaunchSwitches.KEY_BEFORE_MEANING) {
            assertThat(FirstLaunchSwitches.row(key)).get().extracting(Row::status).isEqualTo(Status.WAITING);
            assertThat(existing).contains(key);
        }
    }

    @Test
    void aManualRowIsDeliberatelyNotSetUp() {
        for (Row row : FirstLaunchSwitches.ALL) {
            if (row.status() == Status.MANUAL) {
                assertThat(PARAMS).as("%s is not set up by QB_SETUP", row.key()).doesNotContainKey(row.key());
                assertThat(row.value()).isNull();
            }
        }
    }

    @Test
    void everyKeyIsListedOnce() {
        List<String> keys = FirstLaunchSwitches.ALL.stream().map(Row::key).toList();
        assertThat(keys).doesNotHaveDuplicates();
    }

    @Test
    void onlyProductionAndStagingTakeTheFirstLaunchsValues() {
        assertThat(FirstLaunchSwitches.appliesTo("production")).isTrue();
        assertThat(FirstLaunchSwitches.appliesTo("staging")).isTrue();
        assertThat(FirstLaunchSwitches.appliesTo("test")).isFalse();
        assertThat(FirstLaunchSwitches.appliesTo("local")).isFalse();
        assertThat(FirstLaunchSwitches.appliesTo(null)).isFalse();
    }

    @Test
    void theValueIsTheDeclaredOneUnlessItIsTheFirstLaunchAndTheRowIsActiveWithAValue() {
        QbParams.Param buy = PARAMS.get(QbParams.KUDOS_BUY_ENABLED);
        assertThat(FirstLaunchSwitches.valueFor(buy, false)).isEqualTo(true);
        assertThat(FirstLaunchSwitches.valueFor(buy, true)).isEqualTo(false);
        // An amount keeps the declared value; a parameter with no row does too.
        QbParams.Param basic = PARAMS.get(QbParams.FEE_CREDIT_GRANT_BASIC);
        assertThat(FirstLaunchSwitches.valueFor(basic, true)).isEqualTo(basic.value());
        QbParams.Param other = PARAMS.get(QbParams.TOPUP_MIN);
        assertThat(FirstLaunchSwitches.valueFor(other, true)).isEqualTo(other.value());
    }

    @Test
    void theKudosRewardSwitchIsDeclaredOnAndFirstLaunchOff() {
        QbParams.Param reward = PARAMS.get(QbParams.REWARD_KUDOS_ENABLED);
        assertThat(reward.value()).isEqualTo(true);
        assertThat(FirstLaunchSwitches.valueFor(reward, true)).isEqualTo(false);
        assertThat(QbParams.CONTROLLED).contains(QbParams.REWARD_KUDOS_ENABLED);
    }
}
