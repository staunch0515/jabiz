package com.jabiz.quizbuks.country;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegionsTest {

    @Test
    void targetsAreWrittenInOneOrderWithoutAddingGlobal() {
        assertThat(Regions.targets(List.of("ASIA", "JP"))).isEqualTo("JP,ASIA");
        assertThat(Regions.targets(List.of("GLOBAL"))).isEqualTo("GLOBAL");
        assertThat(Regions.targets(List.of("SOUTH_AMERICA", "GLOBAL", "SOUTH_AMERICA")))
            .isEqualTo("GLOBAL,SOUTH_AMERICA");
        assertThatThrownBy(() -> Regions.targets(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Regions.targets(List.of("JP", "MARS"))).hasMessageContaining("MARS");
    }

    @Test
    void thePatternAcceptsEveryWrittenSetAndNothingElse() {
        // Every non-empty subset, in its written form.
        int all = Regions.ALL.size();
        for (int mask = 1; mask < (1 << all); mask++) {
            List<String> subset = new ArrayList<>();
            for (int i = 0; i < all; i++) {
                if ((mask & (1 << i)) != 0) {
                    subset.add(Regions.ALL.get(i));
                }
            }
            String written = Regions.targets(subset);
            assertThat(Regions.TARGETS.matcher(written).matches()).as(written).isTrue();
            assertThat(written.length()).isLessThanOrEqualTo(Regions.MAX_LENGTH);
        }
        for (String bad : List.of("", "ASIA,JP", "JP,JP", "JP,", ",JP", "jp", "JP, ASIA", "MARS", "GLOBAL,GLOBAL")) {
            assertThat(Regions.TARGETS.matcher(bad).matches()).as(bad).isFalse();
        }
        // Country regions (always with GLOBAL) are targets as well.
        assertThat(Set.of("GLOBAL,JP,ASIA", "GLOBAL,EUROPE,ASIA"))
            .allSatisfy(written -> assertThat(Regions.TARGETS.matcher(written).matches()).isTrue());
    }
}
