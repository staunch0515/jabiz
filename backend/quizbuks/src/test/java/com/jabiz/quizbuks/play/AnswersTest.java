package com.jabiz.quizbuks.play;

import com.jabiz.entity.Violation;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnswersTest {

    /** Question 1 shows options 2, 1; question 2 shows 3, 1, 2. */
    static final List<List<Integer>> ORDER = List.of(List.of(2, 1), List.of(3, 1, 2));

    @Test
    void reportsEveryProblemAtOnce() {
        List<Violation> problems = Answers.check(ORDER, List.of(
            new Answers.Given(3, List.of(1)),
            new Answers.Given(1, List.of(1, 3, 1)),
            new Answers.Given(2, List.of(2)),
            new Answers.Given(2, List.of(1)),
            new Answers.Given(0, List.of())), 5);
        assertThat(problems).extracting(Violation::ruleCode).containsOnly(PlayCodes.ANSWER_INVALID);
        assertThat(problems).extracting(Violation::field).containsExactly("currentIndex", "answers[0].questionNo",
            "answers[1].selected[1]", "answers[1].selected[2]", "answers[3].questionNo", "answers[4].questionNo");
        assertThat(problems.get(2).params()).containsEntry("questionNo", 1).containsEntry("option", 3);
    }

    @Test
    void acceptsValidAnswersAndNullOptions() {
        assertThat(Answers.check(ORDER, List.of(new Answers.Given(1, List.of(1, 2)), new Answers.Given(2, null)), 1))
            .isEmpty();
        assertThat(Answers.check(ORDER, null, null)).isEmpty();
        assertThat(Answers.check(ORDER, List.of(new Answers.Given(1, Arrays.asList((Integer) null))), 0))
            .extracting(Violation::field).containsExactly("answers[0].selected[0]");
    }

    @Test
    void mergeReplacesTheGivenQuestionsSortedAndOnce() {
        List<List<Integer>> answers = Answers.merge(List.of(List.of(1), List.of(3)), 2,
            List.of(new Answers.Given(2, List.of(2, 1))));
        assertThat(answers).containsExactly(List.of(1), List.of(1, 2));
        assertThat(Answers.merge(List.of(), 2, null)).containsExactly(List.of(), List.of());
        assertThat(Answers.merge(answers, 2, List.of(new Answers.Given(1, List.of())))).containsExactly(List.of(),
            List.of(1, 2));
    }

    @Test
    void canonicalJsonRoundTrips() {
        assertThat(Answers.toJson(ORDER)).isEqualTo("[[2,1],[3,1,2]]");
        assertThat(Answers.fromJson("[[2,1],[3,1,2]]")).isEqualTo(ORDER);
        assertThat(Answers.fromJson(null)).isEmpty();
        assertThat(Answers.none(3)).containsExactly(List.of(), List.of(), List.of());
        assertThat(Answers.given(List.of(List.of(), List.of(2)))).containsExactly(new Answers.Given(2, List.of(2)));
    }
}
