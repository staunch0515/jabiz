package com.jabiz.quizbuks.play;

import com.jabiz.quizbuks.content.QuizSnapshot;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * The order an attempt shows each question's options in (docs/quizbuks/plans/Q5-answering.md, D-Q5-1, default answer
 * to Q8): the questions keep their order, the options of each are shuffled. The shuffle is a function of the seed (the
 * attempt's taker and publication, which name one attempt) and the question, so the order is made once and stored,
 * and the same attempt always gets the same one. Answers name options by their number in the version ({@code no}),
 * whatever the order shown.
 */
public final class OptionShuffle {

    /** For each question in order, its options' numbers in the order shown. */
    public static List<List<Integer>> order(String seed, List<QuizSnapshot.Question> questions) {
        long base = seed(seed);
        List<List<Integer>> order = new ArrayList<>();
        for (QuizSnapshot.Question question : questions) {
            List<Integer> numbers = new ArrayList<>(question.options().stream().map(QuizSnapshot.Option::no).toList());
            SplittableRandom random = new SplittableRandom(base * 31 + question.no());
            // Fisher-Yates.
            for (int i = numbers.size() - 1; i > 0; i--) {
                int j = random.nextInt(i + 1);
                Integer swap = numbers.get(i);
                numbers.set(i, numbers.get(j));
                numbers.set(j, swap);
            }
            order.add(List.copyOf(numbers));
        }
        return List.copyOf(order);
    }

    /** The seed of an attempt: its taker and publication. */
    public static String seedOf(String userId, Object publicationId) {
        return userId + "|" + publicationId;
    }

    /** A 64-bit FNV-1a hash of the text: stable across runs and versions of Java, unlike {@code hashCode}. */
    static long seed(String text) {
        long hash = 0xcbf29ce484222325L;
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            hash ^= b & 0xff;
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private OptionShuffle() {}
}
