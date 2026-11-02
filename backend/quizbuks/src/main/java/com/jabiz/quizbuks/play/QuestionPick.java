package com.jabiz.quizbuks.play;

import tools.jackson.core.type.TypeReference;
import com.jabiz.integrity.IntegrityKey;
import com.jabiz.quizbuks.content.QuizSnapshot;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.SplittableRandom;

/**
 * The questions an attempt is given when its publication draws k of the version's n (Q81,
 * docs/quizbuks/plans/Q81-question-pick.md, D-Q81-3): k of them, in the version's order, drawn once when the attempt
 * starts and stored with it ({@code questionSet}, the version's numbers). A taker gets one attempt, so one set.
 *
 * <p>Like {@link OptionShuffle}, the draw is seeded with HMAC-SHA256 over the taker and the publication under a key of
 * the server's, derived from the integrity key under its own label: a taker cannot recompute which questions the next
 * taker gets, and the tests' fixed key keeps their sets the same from run to run.
 */
public final class QuestionPick {

    /** The label the pick's key is derived under: HMAC(integrity key, label). */
    static final String LABEL = "qb.question-pick";
    private static final String ALGORITHM = "HmacSHA256";
    private static final int MIN_KEY_BYTES = 32;
    private static final TypeReference<List<Integer>> NUMBERS = new TypeReference<>() {};

    private final byte[] key;

    /** @param key the pick's own key, at least 32 bytes */
    public QuestionPick(byte[] key) {
        Objects.requireNonNull(key, "key must not be null");
        if (key.length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException("The pick's key must have at least " + MIN_KEY_BYTES + " bytes");
        }
        this.key = key.clone();
    }

    /** The pick keyed from the integrity key, under {@value #LABEL}. */
    public static QuestionPick of(IntegrityKey integrityKey) {
        return new QuestionPick(HexFormat.of().parseHex(integrityKey.sign(LABEL)));
    }

    /**
     * The numbers of {@code count} of {@code questions}, in the order they have there.
     *
     * @throws IllegalArgumentException when {@code count} is not 1..n-1
     */
    public List<Integer> pick(String userId, Object publicationId, List<QuizSnapshot.Question> questions, int count) {
        int n = questions.size();
        if (count < 1 || count >= n) {
            throw new IllegalArgumentException("A pick is 1.." + (n - 1) + " of " + n + " questions, not " + count);
        }
        List<Integer> indexes = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            indexes.add(i);
        }
        SplittableRandom random = new SplittableRandom(seed(userId, publicationId));
        // Fisher-Yates over the indexes; the first count of them are the draw, put back into the version's order.
        for (int i = n - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            Integer swap = indexes.get(i);
            indexes.set(i, indexes.get(j));
            indexes.set(j, swap);
        }
        return indexes.subList(0, count).stream().sorted().map(i -> questions.get(i).no()).toList();
    }

    /** The set as the attempt stores it (JSON of the version's numbers); null for an attempt given every question. */
    public static String toJson(List<Integer> set) {
        return set == null ? null : Answers.json(set);
    }

    /** The set an attempt stores; null when it was given every question (also for attempts from before Q81). */
    public static List<Integer> fromJson(Object json) {
        return json == null || json.toString().isBlank() ? null : List.copyOf(Answers.parse(json.toString(), NUMBERS));
    }

    /** The first 64 bits of HMAC-SHA256(key, {@code userId|publicationId}). */
    long seed(String userId, Object publicationId) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            byte[] digest = mac.doFinal((userId + "|" + publicationId).getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest, 0, Long.BYTES).getLong();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }
}
