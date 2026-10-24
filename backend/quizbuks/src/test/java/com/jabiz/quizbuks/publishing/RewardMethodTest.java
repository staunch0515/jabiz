package com.jabiz.quizbuks.publishing;

import org.junit.jupiter.api.Test;

import java.util.List;

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
        assertThat(RewardMethod.platform(null)).isTrue();
        assertThat(RewardMethod.platform("PLATFORM")).isTrue();
        assertThat(RewardMethod.platform("SPONSOR_CASH")).isFalse();
    }
}
