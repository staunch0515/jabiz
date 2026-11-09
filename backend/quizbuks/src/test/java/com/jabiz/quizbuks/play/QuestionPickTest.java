package com.jabiz.quizbuks.play;

import com.jabiz.quizbuks.content.QuizSnapshot;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Q81 (D-Q81-3): which of a version's questions an attempt is given. */
class QuestionPickTest {

    static final QuestionPick PICK = new QuestionPick(OptionShuffleTest.key("the-question-pick-key-of-these-tests-00001"));

    @Property(tries = 200)
    void aPickIsKDistinctQuestionsInTheVersionsOrder(@ForAll @AlphaChars String user,
        @ForAll @IntRange(min = 2, max = 30) int n, @ForAll @IntRange(min = 1, max = 29) int k) {
        int count = Math.min(k, n - 1);
        List<QuizSnapshot.Question> questions = OptionShuffleTest.questions(n, 2);
        List<Integer> picked = PICK.pick(user, "publication", questions, count);
        assertThat(picked).hasSize(count).doesNotHaveDuplicates().isSorted()
            .allSatisfy(no -> assertThat(no).isBetween(1, n));
        // The same taker of the same publication always gets the same set (a continued start shows the same questions).
        assertThat(PICK.pick(user, "publication", questions, count)).isEqualTo(picked);
    }

    @Test
    void everyQuestionAndAllOfThemAreNotAPick() {
        List<QuizSnapshot.Question> questions = OptionShuffleTest.questions(4, 2);
        assertThatThrownBy(() -> PICK.pick("user", "pub", questions, 4)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PICK.pick("user", "pub", questions, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    /** Different takers get different sets, and the set is not a function of what the taker knows (the server's key). */
    @Test
    void theSetDependsOnTheTakerAndTheServersKey() {
        List<QuizSnapshot.Question> questions = OptionShuffleTest.questions(10, 2);
        Set<List<Integer>> sets = new HashSet<>();
        QuestionPick other = new QuestionPick(OptionShuffleTest.key("another-question-pick-key-of-the-tests-002"));
        int differ = 0;
        for (int i = 0; i < 100; i++) {
            List<Integer> mine = PICK.pick("user-" + i, "pub", questions, 3);
            sets.add(mine);
            if (!mine.equals(other.pick("user-" + i, "pub", questions, 3))) {
                differ++;
            }
        }
        // 120 possible sets: a hundred takers do not all draw the same few.
        assertThat(sets.size()).isGreaterThan(20);
        // Two keys agree on a set of 3 of 10 with probability 1/120: not on most of a hundred.
        assertThat(differ).isGreaterThan(90);
        assertThat(PICK.pick("user", "pub", questions, 3)).isNotEqualTo(PICK.pick("user", "other-pub", questions, 3));
    }

    /** F-S7: six questions, two of each difficulty. */
    private static List<QuizSnapshot.Question> graded() {
        String[] levels = {"EASY", "EASY", "MEDIUM", "MEDIUM", "HARD", "HARD"};
        List<QuizSnapshot.Question> questions = new java.util.ArrayList<>();
        for (QuizSnapshot.Question q : OptionShuffleTest.questions(6, 2)) {
            questions.add(new QuizSnapshot.Question(q.no(), q.stem(), q.image(), q.points(), q.options(), null,
                levels[q.no() - 1]));
        }
        return questions;
    }

    @Property(tries = 100)
    void aDrawByDifficultyTakesEachLevelsCountOfThatLevelOnly(@ForAll @AlphaChars String user,
        @ForAll @IntRange(min = 0, max = 2) int easy, @ForAll @IntRange(min = 0, max = 2) int hard) {
        List<QuizSnapshot.Question> questions = graded();
        java.util.Map<String, Integer> quotas = new java.util.LinkedHashMap<>();
        quotas.put("EASY", easy);
        quotas.put("MEDIUM", 1);
        quotas.put("HARD", hard);
        List<Integer> drawn = PICK.pickByDifficulty(user, "pub", questions, quotas);
        assertThat(drawn).hasSize(easy + 1 + hard).doesNotHaveDuplicates().isSorted();
        assertThat(drawn.stream().filter(no -> no <= 2)).hasSize(easy);
        assertThat(drawn.stream().filter(no -> no == 3 || no == 4)).hasSize(1);
        assertThat(drawn.stream().filter(no -> no >= 5)).hasSize(hard);
        assertThat(PICK.pickByDifficulty(user, "pub", questions, quotas)).isEqualTo(drawn);
        // Shuffled by the order's own seed: the same numbers.
        QuizSnapshot quiz = new QuizSnapshot("t", null, null, null, false, List.of(), questions);
        assertThat(PICK.set(user, "pub", quiz, easy + 1 + hard, quotas)).containsExactlyInAnyOrderElementsOf(drawn);
    }

    @Test
    void aLevelOfFewerQuestionsThanItsCountIsNoDraw() {
        assertThatThrownBy(() -> PICK.pickByDifficulty("user", "pub", graded(), java.util.Map.of("HARD", 3)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theSetIsStoredAsJsonAndNoneForEveryQuestion() {
        assertThat(QuestionPick.toJson(List.of(2, 5, 7))).isEqualTo("[2,5,7]");
        assertThat(QuestionPick.fromJson("[2,5,7]")).containsExactly(2, 5, 7);
        assertThat(QuestionPick.toJson(null)).isNull();
        assertThat(QuestionPick.fromJson(null)).isNull();
        assertThat(QuestionPick.fromJson("")).isNull();
    }
}
