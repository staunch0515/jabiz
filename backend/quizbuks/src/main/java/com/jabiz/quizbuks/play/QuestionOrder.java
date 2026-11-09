package com.jabiz.quizbuks.play;

import com.jabiz.integrity.IntegrityKey;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * The order an attempt gives its questions in (F-S7 §1, docs/quizbuks/plans/impl/F-S7.md): a quiz that shuffles gives
 * each taker the questions (all of them, or the ones drawn, {@link QuestionPick}) in an order of their own; a quiz set
 * to the fixed order, and a survey, in the version's. The order is made once, when the attempt starts, and stored with
 * it ({@code questionSet}); the taker sees the questions numbered by their place in it.
 *
 * <p>Like {@link OptionShuffle} and the draw, the order is seeded with HMAC-SHA256 over the taker and the publication
 * under a key of the server's, derived from the integrity key under its own label: a taker cannot recompute the order
 * of the next one, and the tests' fixed key keeps their orders the same from run to run.
 */
public final class QuestionOrder {

    /** The label the order's key is derived under: HMAC(integrity key, label). */
    static final String LABEL = "qb.question-order";
    private static final String ALGORITHM = "HmacSHA256";
    private static final int MIN_KEY_BYTES = 32;

    private final byte[] key;

    /** @param key the order's own key, at least 32 bytes */
    public QuestionOrder(byte[] key) {
        Objects.requireNonNull(key, "key must not be null");
        if (key.length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException("The order's key must have at least " + MIN_KEY_BYTES + " bytes");
        }
        this.key = key.clone();
    }

    /** The order keyed from the integrity key, under {@value #LABEL}. */
    public static QuestionOrder of(IntegrityKey integrityKey) {
        return new QuestionOrder(HexFormat.of().parseHex(integrityKey.sign(LABEL)));
    }

    /**
     * The question numbers {@code nos} in the order the attempt gives them: shuffled for this taker and publication,
     * or (not {@code shuffled}) in the version's order, by number.
     */
    public List<Integer> order(String userId, Object publicationId, List<Integer> nos, boolean shuffled) {
        List<Integer> numbers = new ArrayList<>(nos);
        numbers.sort(null);
        if (!shuffled) {
            return List.copyOf(numbers);
        }
        java.util.SplittableRandom random = new java.util.SplittableRandom(seed(userId, publicationId));
        // Fisher-Yates over the numbers in the version's order, so the order does not depend on how they were given.
        for (int i = numbers.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            Integer swap = numbers.get(i);
            numbers.set(i, numbers.get(j));
            numbers.set(j, swap);
        }
        return List.copyOf(numbers);
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

    @Override
    public String toString() {
        return "QuestionOrder[key=***]";
    }
}
