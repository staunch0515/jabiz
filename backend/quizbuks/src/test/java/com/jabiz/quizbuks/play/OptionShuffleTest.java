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

    static final OptionShuffle SHUFFLE = new OptionShuffle(key("the-option-shuffle-key-of-these-tests-0001"));

    static byte[] key(String text) {
        return text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

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
        List<List<Integer>> order = SHUFFLE.order(seed, "publication", questions(count, options));
        assertThat(order).hasSize(count);
        for (List<Integer> shown : order) {
            assertThat(shown).hasSize(options).containsExactlyInAnyOrderElementsOf(
                java.util.stream.IntStream.rangeClosed(1, options).boxed().toList());
        }
        // The same attempt always gets the same order.
        assertThat(SHUFFLE.order(seed, "publication", questions(count, options))).isEqualTo(order);
    }

    @Test
    void theSeedIsStable() {
        // Pinned: the same key and attempt always give the same order (the tests' scenarios rely on it).
        assertThat(SHUFFLE.seed("user", "publication", 1)).isEqualTo(SHUFFLE.seed("user", "publication", 1))
            .isNotEqualTo(SHUFFLE.seed("user", "publication", 2));
        assertThat(SHUFFLE.order("user", "publication", questions(2, 4)))
            .isEqualTo(new OptionShuffle(key("the-option-shuffle-key-of-these-tests-0001"))
                .order("user", "publication", questions(2, 4)));
    }

    /**
     * Review M-a: the order is not a function of what the taker knows (their id, the publication, the question):
     * under another key the same attempt is shown another order, so it cannot be recomputed without the server's key.
     */
    @Test
    void theOrderDependsOnTheServersKey() {
        OptionShuffle other = new OptionShuffle(key("another-option-shuffle-key-of-the-tests-2"));
        int differ = 0;
        int attempts = 200;
        for (int i = 0; i < attempts; i++) {
            List<QuizSnapshot.Question> questions = questions(5, 4);
            if (!SHUFFLE.order("user-" + i, "pub", questions).equals(other.order("user-" + i, "pub", questions))) {
                differ++;
            }
            assertThat(SHUFFLE.seed("user-" + i, "pub", 1)).isNotEqualTo(other.seed("user-" + i, "pub", 1));
        }
        // Five questions of four options: two independent keys agree on all five with probability (1/24)^5.
        assertThat(differ).isEqualTo(attempts);
    }

    @Test
    void theKeyIsDerivedFromTheIntegrityKeyUnderItsOwnLabel() {
        com.jabiz.integrity.IntegrityKey integrity = new com.jabiz.integrity.IntegrityKey(
            key("an-integrity-key-of-the-option-shuffle-test"));
        OptionShuffle derived = OptionShuffle.of(integrity);
        OptionShuffle direct = new OptionShuffle(
            java.util.HexFormat.of().parseHex(integrity.sign(OptionShuffle.LABEL)));
        assertThat(derived.order("user", "pub", questions(3, 5)))
            .isEqualTo(direct.order("user", "pub", questions(3, 5)));
        // Not the integrity key itself.
        assertThat(derived.order("user", "pub", questions(3, 5)))
            .isNotEqualTo(new OptionShuffle(key("an-integrity-key-of-the-option-shuffle-test"))
                .order("user", "pub", questions(3, 5)));
        assertThat(derived.toString()).doesNotContain(integrity.sign(OptionShuffle.LABEL));
    }

    @Test
    void aShortKeyIsRefused() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new OptionShuffle(key("short")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void differentAttemptsSpreadTheOptionsEvenly() {
        // Over many attempts, each of four options comes first about a quarter of the time.
        Map<Integer, Integer> first = new HashMap<>();
        int attempts = 4_000;
        for (int i = 0; i < attempts; i++) {
            List<List<Integer>> order = SHUFFLE.order("user-" + i, "pub", questions(1, 4));
            first.merge(order.getFirst().getFirst(), 1, Integer::sum);
        }
        assertThat(first).hasSize(4);
        assertThat(first.values()).allSatisfy(n -> assertThat(n).isBetween(attempts / 4 - 200, attempts / 4 + 200));
    }

    @Test
    void questionsOfOneAttemptAreShuffledIndependently() {
        List<List<Integer>> order = SHUFFLE.order("user", "pub", questions(30, 6));
        assertThat(order.stream().distinct().count()).isGreaterThan(10);
    }
}
