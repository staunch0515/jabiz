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
        // Larger whole numbers are read as they are (holding more back), never as the default; others are 5.
        assertThat(MinGroup.of("1000")).isEqualTo(1000);
        assertThat(MinGroup.of("999999999")).isEqualTo(999_999_999);
        assertThat(MinGroup.of("1000000000")).isEqualTo(5);
        assertThat(MinGroup.of("2.5")).isEqualTo(5);
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
    void questionTotalsRoundTripAndAddPerVersion() {
        String v1 = "0192f0a0-0000-7000-8000-000000000001";
        String v2 = "0192f0a0-0000-7000-8000-000000000002";
        SortedMap<String, QuestionTotals.Version> totals = new TreeMap<>(Map.of(v1, new QuestionTotals.Version(4,
            new TreeMap<>(Map.of(2, new QuestionTotals.Count(4, 1), 1, new QuestionTotals.Count(4, 3))))));
        String json = QuestionTotals.format(totals);
        assertThat(json).isEqualTo("{\"" + v1 + "\":{\"n\":4,\"q\":[[1,4,3],[2,4,1]]}}");
        assertThat(QuestionTotals.parse(json)).isEqualTo(totals);
        assertThat(QuestionTotals.parse(null)).isEmpty();
        assertThat(QuestionTotals.parse("{}")).isEmpty();
        // The migration's JSON (PostgreSQL's spacing) reads the same.
        assertThat(QuestionTotals.parse("{\"" + v1 + "\": {\"n\": 4, \"q\": [[1, 4, 3], [2, 4, 1]]}}"))
            .isEqualTo(totals);
        // Increments add to their own version's questions; another version's numbers are other questions (Q51b).
        SortedMap<String, QuestionTotals.Version> sum = QuestionTotals.add(totals, Map.of(
            v1, new QuestionTotals.Version(1, new TreeMap<>(Map.of(1, new QuestionTotals.Count(1, 1),
                3, new QuestionTotals.Count(1, 0)))),
            v2, new QuestionTotals.Version(2, new TreeMap<>(Map.of(1, new QuestionTotals.Count(2, 0))))));
        assertThat(sum.get(v1).attempts()).isEqualTo(5);
        assertThat(sum.get(v1).questions()).containsEntry(1, new QuestionTotals.Count(5, 4))
            .containsEntry(2, new QuestionTotals.Count(4, 1)).containsEntry(3, new QuestionTotals.Count(1, 0));
        assertThat(sum.get(v2)).isEqualTo(new QuestionTotals.Version(2,
            new TreeMap<>(Map.of(1, new QuestionTotals.Count(2, 0)))));
        assertThat(QuestionTotals.format(sum)).startsWith("{\"" + v1 + "\":").contains("\"" + v2 + "\":{\"n\":2,");
        // A hundred versions of a hundred questions of the largest counts fit the column; more do not.
        SortedMap<Integer, QuestionTotals.Count> questions = new TreeMap<>();
        for (int no = 1; no <= 100; no++) {
            questions.put(no, new QuestionTotals.Count(999_999_999L, 999_999_999L));
        }
        SortedMap<String, QuestionTotals.Version> most = new TreeMap<>();
        for (int i = 0; i < 100; i++) {
            most.put(java.util.UUID.randomUUID().toString(), new QuestionTotals.Version(999_999_999L, questions));
        }
        assertThat(QuestionTotals.format(most).length()).isLessThanOrEqualTo(QuestionTotals.MAX_LENGTH);
        for (int i = 0; i < 60; i++) {
            most.put(java.util.UUID.randomUUID().toString(), new QuestionTotals.Version(999_999_999L, questions));
        }
        assertThatThrownBy(() -> QuestionTotals.format(most)).isInstanceOf(IllegalStateException.class);
    }
}
