package com.jabiz.quizbuks.publishing;

import com.jabiz.param.ParamValues;
import com.jabiz.quizbuks.setup.QbParams;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RewardMethodTest {

    @Test
    void aSettingIsWrittenInTheOrderOfTheMethodsAndReadBack() {
        assertThat(RewardMethod.format(List.of("OFFLINE_REDEEM", "PLATFORM"))).isEqualTo("PLATFORM,OFFLINE_REDEEM");
        assertThat(RewardMethod.format(List.of("SPONSOR_CASH", "SPONSOR_CASH"))).isEqualTo("SPONSOR_CASH");
        assertThat(RewardMethod.parse("PLATFORM,SPONSOR_CASH")).containsExactly("PLATFORM", "SPONSOR_CASH");
        assertThat(RewardMethod.parse(" SPONSOR_CASH , SPONSOR_CASH ")).containsExactly("SPONSOR_CASH");
    }

    @Test
    void withoutASettingOnlyThePlatformPays() {
        assertThat(RewardMethod.parse(null)).containsExactly("PLATFORM");
        assertThat(RewardMethod.parse("  ")).containsExactly("PLATFORM");
    }

    @Test
    void theDefaultComesFromTheParameterKnownMethodsOnlyInTheFixedOrder() {
        assertThat(RewardMethod.defaultMethods(null)).containsExactly("PLATFORM");
        assertThat(RewardMethod.defaultMethods(values(Map.of()))).containsExactly("PLATFORM");
        assertThat(RewardMethod.defaultMethods(values(Map.of(QbParams.REWARD_DEFAULT_METHODS, " "))))
            .containsExactly("PLATFORM");
        assertThat(RewardMethod.defaultMethods(values(Map.of(QbParams.REWARD_DEFAULT_METHODS,
            "OFFLINE_REDEEM, bogus, PLATFORM")))).containsExactly("PLATFORM", "OFFLINE_REDEEM");
        // A value naming no method allows nothing: less, never more.
        assertThat(RewardMethod.defaultMethods(values(Map.of(QbParams.REWARD_DEFAULT_METHODS, "bogus")))).isEmpty();
    }

    @Test
    void nullIsThePlatformsMethod() {
        assertThat(RewardMethod.platform(null)).isTrue();
        assertThat(RewardMethod.platform("PLATFORM")).isTrue();
        assertThat(RewardMethod.platform("SPONSOR_CASH")).isFalse();
    }

    private static ParamValues values(Map<String, Object> values) {
        return new ParamValues(Instant.EPOCH, values);
    }
}
