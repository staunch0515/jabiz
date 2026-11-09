package com.jabiz.quizbuks.play;

import com.jabiz.quizbuks.content.QuizSnapshot;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** F-S7 (docs/quizbuks/plans/impl/F-S7.md §1): the order an attempt gives its questions in. */
class QuestionOrderTest {

    static final QuestionOrder ORDER = new QuestionOrder(OptionShuffleTest.key("the-question-order-key-of-these-tests-001"));

    private static List<Integer> numbers(int n) {
        return IntStream.rangeClosed(1, n).boxed().toList();
    }

    @Property(tries = 200)
    void anOrderIsAPermutationOfTheNumbersGivenTheSameForTheSameTakerAndPublication(@ForAll @AlphaChars String user,
        @ForAll @IntRange(min = 1, max = 60) int n) {
        List<Integer> order = ORDER.order(user, "publication", numbers(n), true);
        assertThat(order).containsExactlyInAnyOrderElementsOf(numbers(n));
        assertThat(ORDER.order(user, "publication", numbers(n), true)).isEqualTo(order);
    }

    @Property(tries = 100)
    void theOrderDoesNotDependOnHowTheNumbersAreGiven(@ForAll @AlphaChars String user,
        @ForAll @IntRange(min = 2, max = 30) int n) {
        List<Integer> reversed = new ArrayList<>(numbers(n));
        Collections.reverse(reversed);
        assertThat(ORDER.order(user, "p", reversed, true)).isEqualTo(ORDER.order(user, "p", numbers(n), true));
    }

    @Test
    void differentTakersGetDifferentOrders() {
        Set<List<Integer>> orders = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            orders.add(ORDER.order("user-" + i, "pub", numbers(6), true));
        }
        // 720 orders of six: a hundred takers do not share a few of them.
        assertThat(orders.size()).isGreaterThan(80);
        assertThat(ORDER.order("user", "pub", numbers(10), true))
            .isNotEqualTo(ORDER.order("user", "other-pub", numbers(10), true));
    }

    @Test
    void theFixedOrderIsTheVersions() {
        assertThat(ORDER.order("user", "pub", List.of(5, 2, 9), false)).containsExactly(2, 5, 9);
    }

    @Test
    void aSurveyAndAFixedQuizKeepTheirOrderAndGiveNoSetWithoutAPick() {
        QuestionPick pick = QuestionPickTest.PICK;
        List<QuizSnapshot.Question> questions = OptionShuffleTest.questions(5, 2);
        QuizSnapshot quiz = new QuizSnapshot("t", null, null, null, false, List.of(), questions);
        QuizSnapshot fixed = quiz.withFixedOrder(true);
        QuizSnapshot survey = new QuizSnapshot("t", null, null, null, false, List.of(), questions, true);
        assertThat(quiz.shuffled()).isTrue();
        assertThat(fixed.shuffled()).isFalse();
        assertThat(survey.shuffled()).isFalse();
        // Shuffled without a pick: every question, in an order of the taker's own.
        assertThat(pick.set("user", "pub", quiz, null, Map.of())).containsExactlyInAnyOrderElementsOf(numbers(5));
        // In the fixed order and without a pick nothing is stored, as before F-S7; a survey likewise.
        assertThat(pick.set("user", "pub", fixed, null, Map.of())).isNull();
        assertThat(pick.set("user", "pub", survey, null, Map.of())).isNull();
        // A pick in the fixed order is in the version's order.
        assertThat(pick.set("user", "pub", fixed, 3, Map.of())).hasSize(3).isSorted();
    }

    @Test
    void theKeyIsNotShown() {
        assertThat(ORDER.toString()).doesNotContain("key-of-these-tests").contains("***");
    }
}
