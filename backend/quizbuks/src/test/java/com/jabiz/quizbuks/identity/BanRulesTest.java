package com.jabiz.quizbuks.identity;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BanRulesTest {

    @Test
    void noRowIsNoBan() {
        assertThat(BanRules.refuses(List.of())).isFalse();
        assertThat(BanRules.refuses(null)).isFalse();
        assertThat(BanRules.banned(null)).isFalse();
    }

    @Test
    void aBannedRowRefuses() {
        assertThat(BanRules.refuses(List.of(Map.of("banned", true, "reason", "Spam")))).isTrue();
    }

    @Test
    void aLiftedOrNeverBannedRowAllows() {
        assertThat(BanRules.refuses(List.of(Map.of("banned", false, "liftNote", "Appeal accepted")))).isFalse();
        Map<String, Object> unknown = new HashMap<>();
        unknown.put("banned", null);
        assertThat(BanRules.refuses(List.of(unknown))).isFalse();
        // Only the value true bans: a text "true" is not what the platform reads for a bool.
        assertThat(BanRules.refuses(List.of(Map.of("banned", "true")))).isFalse();
    }

    @Test
    void anyBannedRowOfSeveralRefuses() {
        assertThat(BanRules.refuses(List.of(Map.of("banned", false), Map.of("banned", true)))).isTrue();
        assertThat(BanRules.refuses(List.of(Map.of("banned", false), Map.of("banned", false)))).isFalse();
    }
}
