package com.jabiz.quizbuks.stats;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MinGroupTest {

    @Test
    void kIsTheParameterNeverBelowTheFloorAndFiveWhenNotSet() {
        assertThat(MinGroup.of(null)).isEqualTo(5);
        assertThat(MinGroup.of("")).isEqualTo(5);
        assertThat(MinGroup.of("ten")).isEqualTo(5);
        assertThat(MinGroup.of("-1")).isEqualTo(5);
        assertThat(MinGroup.of("1")).isEqualTo(3);
        assertThat(MinGroup.of("3")).isEqualTo(3);
        assertThat(MinGroup.of("10")).isEqualTo(10);
        assertThat(MinGroup.of(new BigDecimal("7"))).isEqualTo(7);
        assertThat(MinGroup.of(2)).isEqualTo(3);
    }

    @Test
    void figuresAreShownOverKCompletionsOnly() {
        assertThat(MinGroup.show(4, 5)).isFalse();
        assertThat(MinGroup.show(5, 5)).isTrue();
        // Never below the floor, whatever k is passed.
        assertThat(MinGroup.show(2, 1)).isFalse();
        assertThat(MinGroup.show(3, 1)).isTrue();
    }

    @Test
    void shownFiguresChangeOnlyAfterKNewCompletions() {
        // The first k make the first figures; one more never changes them (two looks would tell its answers).
        assertThat(MinGroup.publish(4, 0, 5)).isFalse();
        assertThat(MinGroup.publish(5, 0, 5)).isTrue();
        assertThat(MinGroup.publish(6, 5, 5)).isFalse();
        assertThat(MinGroup.publish(9, 5, 5)).isFalse();
        assertThat(MinGroup.publish(10, 5, 5)).isTrue();
        // A recount below what was shown (it cannot happen) changes nothing either.
        assertThat(MinGroup.publish(4, 5, 5)).isFalse();
        assertThat(MinGroup.publish(4, 1, 1)).isTrue();
        assertThat(MinGroup.publish(3, 1, 1)).isFalse();
    }

    @Test
    void questionTotalsRoundTripAndAdd() {
        SortedMap<Integer, QuestionTotals.Count> totals = new TreeMap<>(Map.of(2, new QuestionTotals.Count(4, 1),
            1, new QuestionTotals.Count(4, 3)));
        String json = QuestionTotals.format(totals);
        assertThat(json).isEqualTo("[[1,4,3],[2,4,1]]");
        assertThat(QuestionTotals.parse(json)).isEqualTo(totals);
        assertThat(QuestionTotals.parse(null)).isEmpty();
        assertThat(QuestionTotals.parse("[]")).isEmpty();
        assertThat(QuestionTotals.add(totals, Map.of(1, new QuestionTotals.Count(1, 1), 3,
            new QuestionTotals.Count(1, 0))))
            .containsEntry(1, new QuestionTotals.Count(5, 4)).containsEntry(2, new QuestionTotals.Count(4, 1))
            .containsEntry(3, new QuestionTotals.Count(1, 0));
        // A hundred questions of the largest counts fit the column.
        SortedMap<Integer, QuestionTotals.Count> most = new TreeMap<>();
        for (int no = 1; no <= 100; no++) {
            most.put(no, new QuestionTotals.Count(999_999_999L, 999_999_999L));
        }
        assertThat(QuestionTotals.format(most).length()).isLessThanOrEqualTo(QuestionTotals.MAX_LENGTH);
        for (int no = 101; no <= 200; no++) {
            most.put(no, new QuestionTotals.Count(999_999_999L, 999_999_999L));
        }
        assertThatThrownBy(() -> QuestionTotals.format(most)).isInstanceOf(IllegalStateException.class);
    }
}
