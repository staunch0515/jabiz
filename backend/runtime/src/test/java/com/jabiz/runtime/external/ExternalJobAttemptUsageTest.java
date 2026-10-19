package com.jabiz.runtime.external;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How the rows of one statement (attempts joined to their usage) become the totals and the count of attempts without
 * recorded usage (decision D52 item 5); the statement itself runs in {@code ExternalJobUnrecordedIT}.
 */
class ExternalJobAttemptUsageTest {

    private static Map<String, Object> row(int attemptNo, String outcome, String metric, Long quantity) {
        Map<String, Object> row = new HashMap<>();
        row.put("attempt_no", attemptNo);
        row.put("outcome", outcome);
        row.put("metric", metric);
        row.put("quantity", quantity);
        return row;
    }

    @Test
    void onlyExpiredAndTimedOutAttemptsWithoutUsageAreUnrecorded() {
        ExternalJobRecorder.AttemptUsage usage = ExternalJobRecorder.attemptUsage(List.of(
            row(1, "LEASE_EXPIRED", null, null),
            row(2, "TIMED_OUT", null, null),
            row(3, "TIMED_OUT", "units", 4L),
            row(4, "FAILED", null, null),
            row(5, "LEASE_EXPIRED", "calls", 1L),
            row(5, "LEASE_EXPIRED", "units", 2L),
            row(6, "SUCCEEDED", "units", 3L)));
        assertThat(usage.unrecordedAttempts()).isEqualTo(2);
        assertThat(usage.usage()).containsExactlyInAnyOrderEntriesOf(Map.of("units", 9L, "calls", 1L));
    }

    @Test
    void aTotalPastTheLargestLongStopsThereRatherThanFailing() {
        ExternalJobRecorder.AttemptUsage usage = ExternalJobRecorder.attemptUsage(List.of(
            row(1, "SUCCEEDED", "units", Long.MAX_VALUE - 1),
            row(2, "SUCCEEDED", "units", 5L)));
        assertThat(usage.usage()).containsEntry("units", Long.MAX_VALUE);
        assertThat(ExternalJobRecorder.saturatedSum(2, 3)).isEqualTo(5);
    }

    @Test
    void noAttemptsIsNothing() {
        ExternalJobRecorder.AttemptUsage usage = ExternalJobRecorder.attemptUsage(List.of());
        assertThat(usage.unrecordedAttempts()).isZero();
        assertThat(usage.usage()).isEmpty();
    }
}
