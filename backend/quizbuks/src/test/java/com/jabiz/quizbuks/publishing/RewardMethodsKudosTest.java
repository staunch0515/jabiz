package com.jabiz.quizbuks.publishing;

import com.jabiz.param.ParamValues;
import com.jabiz.quizbuks.setup.QbParams;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** F-F10a: the Kudos reward switch takes the platform's method from every source, and only when it is off. */
class RewardMethodsKudosTest {

    private static final List<String> ALL = List.of("PLATFORM", "SPONSOR_CASH", "OFFLINE_REDEEM", "DIGITAL_PRIZE");

    private static ParamValues values(Object kudos) {
        return new ParamValues(Instant.EPOCH, Map.of(QbParams.REWARD_KUDOS_ENABLED, kudos));
    }

    @Test
    void offTakesThePlatformMethodAwayWhateverGaveIt() {
        assertThat(RewardMethod.withKudosSwitch(ALL, values(false), false))
            .containsExactly("SPONSOR_CASH", "OFFLINE_REDEEM", "DIGITAL_PRIZE");
        // By the default, by the sponsor's own setting, and by a lit gate: all three sources go through the same list.
        List<String> byDefault = RewardMethod.available(List.of("PLATFORM"), List.of(), new HashSet<>());
        List<String> byOwn = RewardMethod.available(List.of(), List.of("PLATFORM", "SPONSOR_CASH"), new HashSet<>());
        List<String> byGate = RewardMethod.available(List.of(), List.of(), new HashSet<>(List.of(QbParams.TREE_GATE_OFFLINE_REDEEM)));
        assertThat(RewardMethod.withKudosSwitch(byDefault, values(false), false)).isEmpty();
        assertThat(RewardMethod.withKudosSwitch(byOwn, values(false), false)).containsExactly("SPONSOR_CASH");
        assertThat(RewardMethod.withKudosSwitch(byGate, values(false), false)).containsExactly("OFFLINE_REDEEM");
    }

    @Test
    void onChangesNothing() {
        assertThat(RewardMethod.withKudosSwitch(ALL, values(true), false)).isEqualTo(ALL);
    }

    @Test
    void notThereChangesNothingEither() {
        // Before QB_SETUP the parameter does not exist: declared behaviour, as the default methods' parameter gives.
        assertThat(RewardMethod.withKudosSwitch(ALL, new ParamValues(Instant.EPOCH, Map.of()), false)).isEqualTo(ALL);
        assertThat(RewardMethod.withKudosSwitch(ALL, null, false)).isEqualTo(ALL);
    }

    @Test
    void onAFirstLaunchSystemNotThereCountsAsOff() {
        ParamValues none = new ParamValues(Instant.EPOCH, Map.of());
        assertThat(RewardMethod.kudosOff(none, true)).isTrue();
        assertThat(RewardMethod.kudosOff(null, true)).isTrue();
        assertThat(RewardMethod.withKudosSwitch(ALL, none, true)).containsExactly("SPONSOR_CASH", "OFFLINE_REDEEM", "DIGITAL_PRIZE");
        // Published, the value decides, as everywhere.
        assertThat(RewardMethod.kudosOff(values(true), true)).isFalse();
        assertThat(RewardMethod.withKudosSwitch(ALL, values(true), true)).isEqualTo(ALL);
        assertThat(RewardMethod.kudosOff(values(false), true)).isTrue();
        // Any other system: not there is not off.
        assertThat(RewardMethod.kudosOff(none, false)).isFalse();
    }

    @Test
    void theDefaultMethodsFallbackToThePlatformIsBlockedOnAFirstLaunchSystem() {
        ParamValues none = new ParamValues(Instant.EPOCH, Map.of());
        assertThat(RewardMethod.defaultMethods(none)).containsExactly("PLATFORM");
        assertThat(RewardMethod.defaultMethods(none, false)).containsExactly("PLATFORM");
        assertThat(RewardMethod.defaultMethods(none, true)).isEmpty();
        assertThat(RewardMethod.defaultMethods(null, true)).isEmpty();
        // A published value is read the same on both.
        ParamValues named = new ParamValues(Instant.EPOCH, Map.of(QbParams.REWARD_DEFAULT_METHODS, "SPONSOR_CASH"));
        assertThat(RewardMethod.defaultMethods(named, true)).containsExactly("SPONSOR_CASH");
        ParamValues blank = new ParamValues(Instant.EPOCH, Map.of(QbParams.REWARD_DEFAULT_METHODS, ""));
        assertThat(RewardMethod.defaultMethods(blank, true)).isEmpty();
        assertThat(RewardMethod.defaultMethods(blank, false)).isEmpty();
    }
}
