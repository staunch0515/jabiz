package com.jabiz.context;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Data periods (docs/design/10-security.md section 13.2, decision D28 item 8). */
class DataPeriodTest {

    private static final Instant Y2025 = Instant.parse("2025-01-01T00:00:00Z");
    private static final Instant Y2026 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant Y2027 = Instant.parse("2027-01-01T00:00:00Z");

    @Test
    void aPeriodIncludesItsStartAndExcludesItsEnd() {
        DataPeriod fiscal2026 = new DataPeriod(Y2026, Y2027);
        assertThat(fiscal2026.contains(Y2026)).isTrue();
        assertThat(fiscal2026.contains(Y2027.minusNanos(1))).isTrue();
        assertThat(fiscal2026.contains(Y2027)).isFalse();
        assertThat(fiscal2026.contains(Y2026.minusNanos(1))).isFalse();
        assertThat(fiscal2026.contains(null)).isFalse();
        assertThat(new DataPeriod(null, Y2026).contains(Instant.EPOCH)).isTrue();
        assertThat(new DataPeriod(Y2026, null).contains(Instant.parse("2999-01-01T00:00:00Z"))).isTrue();
    }

    @Test
    void aPeriodNeedsAnEndAndAnOrder() {
        assertThatThrownBy(() -> new DataPeriod(null, null)).hasMessageContaining("no period means no limit");
        assertThatThrownBy(() -> new DataPeriod(Y2026, Y2026)).hasMessageContaining("end after it starts");
        assertThat(DataPeriod.of(null, null)).isNull();
        assertThat(DataPeriod.of(Y2026, null)).isEqualTo(new DataPeriod(Y2026, null));
    }

    @Test
    void theAssignmentsGiveTheirSpanAndOneUnlimitedAssignmentLiftsTheLimit() {
        assertThat(DataPeriod.hull(List.of())).isNull();
        assertThat(DataPeriod.hull(List.of(new DataPeriod(Y2026, Y2027)))).isEqualTo(new DataPeriod(Y2026, Y2027));
        // A gap between the assignments is covered: permissions are not split per assignment.
        assertThat(DataPeriod.hull(List.of(new DataPeriod(Y2026, Y2027), new DataPeriod(Y2025,
            Y2025.plusSeconds(60))))).isEqualTo(new DataPeriod(Y2025, Y2027));
        assertThat(DataPeriod.hull(List.of(new DataPeriod(Y2026, Y2027), new DataPeriod(null, Y2025))))
            .isEqualTo(new DataPeriod(null, Y2027));
        assertThat(DataPeriod.hull(List.of(new DataPeriod(Y2026, null), new DataPeriod(null, Y2025)))).isNull();
        assertThat(DataPeriod.hull(Arrays.asList(new DataPeriod(Y2026, Y2027), null))).isNull();
    }
}
