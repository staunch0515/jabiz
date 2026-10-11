package com.jabiz.quizbuks.play;

import com.jabiz.quizbuks.content.QuizSnapshot;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OptionShuffleTest {

    static List<QuizSnapshot.Question> questions(int count, int options) {
        List<QuizSnapshot.Question> questions = new ArrayList<>();
        for (int q = 1; q <= count; q++) {
            List<QuizSnapshot.Option> list = new ArrayList<>();
            for (int o = 1; o <= options; o++) {
                list.add(new QuizSnapshot.Option(o, "o" + o, null, o == 1));
            }
            questions.add(new QuizSnapshot.Question(q, "q" + q, null, 1, list));
        }
        return questions;
    }

    @Property(tries = 200)
    void everyQuestionGetsAPermutationOfItsOptions(@ForAll @AlphaChars String seed,
        @ForAll @IntRange(min = 1, max = 20) int count, @ForAll @IntRange(min = 2, max = 6) int options) {
        List<List<Integer>> order = OptionShuffle.order(seed, questions(count, options));
        assertThat(order).hasSize(count);
        for (List<Integer> shown : order) {
            assertThat(shown).hasSize(options).containsExactlyInAnyOrderElementsOf(
                java.util.stream.IntStream.rangeClosed(1, options).boxed().toList());
        }
        // The same attempt always gets the same order.
        assertThat(OptionShuffle.order(seed, questions(count, options))).isEqualTo(order);
    }

    @Test
    void theSeedIsStable() {
        // Pinned: a stored order must never change meaning between releases.
        assertThat(OptionShuffle.seed("")).isEqualTo(0xcbf29ce484222325L);
        assertThat(OptionShuffle.order("user|publication", questions(2, 4)))
            .isEqualTo(OptionShuffle.order("user|publication", questions(2, 4)));
    }

    @Test
    void differentAttemptsSpreadTheOptionsEvenly() {
        // Over many attempts, each of four options comes first about a quarter of the time.
        Map<Integer, Integer> first = new HashMap<>();
        int attempts = 4_000;
        for (int i = 0; i < attempts; i++) {
            List<List<Integer>> order = OptionShuffle.order(OptionShuffle.seedOf("user-" + i, "pub"),
                questions(1, 4));
            first.merge(order.getFirst().getFirst(), 1, Integer::sum);
        }
        assertThat(first).hasSize(4);
        assertThat(first.values()).allSatisfy(n -> assertThat(n).isBetween(attempts / 4 - 200, attempts / 4 + 200));
    }

    @Test
    void questionsOfOneAttemptAreShuffledIndependently() {
        List<List<Integer>> order = OptionShuffle.order("seed", questions(30, 6));
        assertThat(order.stream().distinct().count()).isGreaterThan(10);
    }
}
