package com.jabiz.quizbuks.play;

import com.jabiz.quizbuks.content.QbContent;
import com.jabiz.quizbuks.content.QuizSnapshot;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ScoringTest {

    static QuizSnapshot.Option option(int no, boolean correct) {
        return new QuizSnapshot.Option(no, "o" + no, null, correct);
    }

    /** Question 1 (2 points): option 1 alone; question 2 (3 points): options 1 and 3 (multiple choice). */
    static QuizSnapshot quiz() {
        return new QuizSnapshot("Q", null, null, 300, false, List.of(), List.of(
            new QuizSnapshot.Question(1, "a", null, 2, List.of(option(1, true), option(2, false))),
            new QuizSnapshot.Question(2, "b", null, 3, List.of(option(1, true), option(2, false), option(3, true)))));
    }

    @Test
    void aQuestionEarnsItsPointsOnlyWhenTheChoiceIsExactlyTheCorrectOptions() {
        assertThat(Scoring.score(quiz(), List.of(List.of(1), List.of(1, 3))).score()).isEqualTo(5);
        assertThat(Scoring.score(quiz(), List.of(List.of(1), List.of(1))).score()).isEqualTo(2);
        assertThat(Scoring.score(quiz(), List.of(List.of(1, 2), List.of(1, 2, 3))).score()).isZero();
        assertThat(Scoring.score(quiz(), List.of(List.of(2), List.of(3, 1))).score()).isEqualTo(3);
    }

    @Test
    void unansweredQuestionsEarnNothing() {
        Scoring.Result result = Scoring.score(quiz(), List.of());
        assertThat(result.score()).isZero();
        assertThat(result.fullScore()).isEqualTo(5);
        assertThat(result.questions()).extracting(Scoring.QuestionResult::selected).containsOnly(List.of());
        assertThat(Scoring.score(quiz(), List.of(List.of(), List.of())).score()).isZero();
    }

    @Test
    void anOptionChosenTwiceCountsOnce() {
        assertThat(Scoring.score(quiz(), List.of(List.of(1, 1), List.of(3, 1, 3))).score()).isEqualTo(5);
    }

    @Test
    void theResultNamesTheCorrectOptionsAndIsCanonicalJson() {
        Scoring.Result result = Scoring.score(quiz(), List.of(List.of(2), List.of(1, 3)));
        assertThat(result.questions()).extracting(Scoring.QuestionResult::correct)
            .containsExactly(List.of(1), List.of(1, 3));
        assertThat(result.questions()).extracting(Scoring.QuestionResult::earned).containsExactly(0, 3);
        assertThat(Scoring.toJson(result)).isEqualTo("[{\"correct\":[1],\"earned\":0,\"no\":1,\"points\":2,"
            + "\"selected\":[2]},{\"correct\":[1,3],\"earned\":3,\"no\":2,\"points\":3,\"selected\":[1,3]}]");
    }

    /** The same rule written another way: for each question, every option chosen iff it is correct. */
    static int reference(QuizSnapshot quiz, List<List<Integer>> answers) {
        int score = 0;
        for (int i = 0; i < quiz.questions().size(); i++) {
            QuizSnapshot.Question question = quiz.questions().get(i);
            Set<Integer> chosen = new HashSet<>(i < answers.size() ? answers.get(i) : List.of());
            boolean right = !chosen.isEmpty();
            for (QuizSnapshot.Option option : question.options()) {
                right &= option.correct() == chosen.contains(option.no());
            }
            right &= chosen.stream().allMatch(no -> question.options().stream().anyMatch(o -> o.no() == no));
            score += right ? question.points() : 0;
        }
        return score;
    }

    @Property(tries = 300)
    void agreesWithAnIndependentReading(@ForAll("quizzes") QuizSnapshot quiz, @ForAll("choices") List<List<Integer>> answers) {
        Scoring.Result result = Scoring.score(quiz, answers);
        assertThat(result.score()).isEqualTo(reference(quiz, answers));
        assertThat(result.score()).isBetween(0, result.fullScore());
        assertThat(result.fullScore()).isEqualTo(quiz.fullScore());
    }

    @Provide
    Arbitrary<QuizSnapshot> quizzes() {
        Arbitrary<QuizSnapshot.Question> question = Combinators.combine(Arbitraries.integers().between(1, 10),
                Arbitraries.integers().between(2, 6), Arbitraries.integers().between(1, 63))
            .as((points, count, mask) -> {
                List<QuizSnapshot.Option> options = new ArrayList<>();
                boolean any = false;
                for (int no = 1; no <= count; no++) {
                    boolean correct = (mask & (1 << (no - 1))) != 0;
                    any |= correct;
                    options.add(option(no, correct));
                }
                if (!any) {
                    options.set(0, option(1, true));
                }
                return new QuizSnapshot.Question(0, "q", null, points, options);
            });
        return question.list().ofMinSize(1).ofMaxSize(6).map(questions -> {
            List<QuizSnapshot.Question> numbered = new ArrayList<>();
            for (int i = 0; i < questions.size(); i++) {
                QuizSnapshot.Question q = questions.get(i);
                numbered.add(new QuizSnapshot.Question(i + 1, q.stem(), null, q.points(), q.options()));
            }
            return new QuizSnapshot("Q", null, null, 60, false, List.of(), numbered);
        });
    }

    @Test
    void scoresAnswersByPositionAndReportsPositions() {
        // Question 1 shows option 2 then 1; question 2 shows 3, 1, 2.
        List<List<Integer>> order = List.of(List.of(2, 1), List.of(3, 1, 2));
        // Position 2 of question 1 is option 1 (correct); positions 1 and 2 of question 2 are options 3 and 1.
        Scoring.Result result = Scoring.scoreShown(quiz(), order, List.of(List.of(2), List.of(1, 2)));
        assertThat(result.score()).isEqualTo(5);
        assertThat(result.questions()).extracting(Scoring.QuestionResult::selected)
            .containsExactly(List.of(2), List.of(1, 2));
        assertThat(result.questions()).extracting(Scoring.QuestionResult::correct)
            .containsExactly(List.of(2), List.of(1, 2));
        // The version's numbers would be wrong answers here.
        assertThat(Scoring.scoreShown(quiz(), order, List.of(List.of(1), List.of(1, 3))).score()).isZero();
    }

    /**
     * Whatever order the options are shown in, choosing the positions of the correct options scores the same as
     * choosing the version's numbers, and the result's positions name exactly the options chosen (M1).
     */
    @Property(tries = 300)
    void positionsMapBackWhateverTheOrder(@ForAll("quizzes") QuizSnapshot quiz,
        @ForAll("choices") List<List<Integer>> answers, @ForAll long seed) {
        List<List<Integer>> order = OptionShuffleTest.SHUFFLE.order("user", Long.toString(seed), quiz.questions());
        List<List<Integer>> shown = new ArrayList<>();
        for (int i = 0; i < answers.size() && i < quiz.questions().size(); i++) {
            shown.add(Answers.positions(order.get(i), answers.get(i)));
        }
        List<List<Integer>> inVersion = Answers.inVersion(order, shown);
        Scoring.Result byPosition = Scoring.scoreShown(quiz, order, shown);
        Scoring.Result byNumber = Scoring.score(quiz, inVersion);
        assertThat(byPosition.score()).isEqualTo(byNumber.score()).isEqualTo(reference(quiz, inVersion));
        for (int i = 0; i < quiz.questions().size(); i++) {
            Scoring.QuestionResult question = byPosition.questions().get(i);
            assertThat(question.selected()).isEqualTo(i < shown.size() ? shown.get(i) : List.of());
            assertThat(Answers.inVersion(List.of(order.get(i)), List.of(question.correct())).get(0))
                .isEqualTo(byNumber.questions().get(i).correct());
        }
    }

    @Provide
    Arbitrary<List<List<Integer>>> choices() {
        return Arbitraries.integers().between(1, 6).list().ofMaxSize(4).list().ofMaxSize(7);
    }

    /** Question 1 single choice, question 2 multiple choice; no correct options, a point each (Q12). */
    static QuizSnapshot survey() {
        return new QuizSnapshot("S", null, null, 300, false, List.of(), List.of(
            new QuizSnapshot.Question(1, "a", null, 1, List.of(option(1, false), option(2, false)), QbContent.SINGLE),
            new QuizSnapshot.Question(2, "b", null, 1, List.of(option(1, false), option(2, false), option(3, false)),
                QbContent.MULTIPLE)), true);
    }

    @Test
    void aSurveysQuestionEarnsItsPointWhenAnsweredWhateverTheAnswer() {
        assertThat(Scoring.score(survey(), List.of(List.of(2), List.of(1, 3))).score()).isEqualTo(2);
        assertThat(Scoring.score(survey(), List.of(List.of(1), List.of(2))).score()).isEqualTo(2);
        // Unanswered, or a single choice with two options (saved, then the deadline passed): no point.
        assertThat(Scoring.score(survey(), List.of(List.of(), List.of(2))).score()).isEqualTo(1);
        assertThat(Scoring.score(survey(), List.of(List.of(1, 2), List.of(2))).score()).isEqualTo(1);
        Scoring.Result result = Scoring.score(survey(), List.of(List.of(1), List.of(3)));
        assertThat(result.fullScore()).isEqualTo(2);
        assertThat(result.questions()).extracting(Scoring.QuestionResult::correct).containsOnly(List.of());
    }
}
