package com.jabiz.quizbuks.play;

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
 * The order an attempt shows each question's options in (docs/quizbuks/plans/Q5-answering.md, D-Q5-1, default answer
 * to Q8): the questions keep their order, the options of each are shuffled. The order is made once, when the attempt
 * starts, and stored with it ({@code optionOrder}, sensitive); answers name options by their position in it (Q10b,
 * M1).
 *
 * <p>The shuffle of a question is seeded with HMAC-SHA256 over the attempt's taker, publication and the question's
 * number, under a key of the server's (Q10b, review M-a): a taker knows all three, so a seed of them alone would let
 * them recompute the order, and with it which position is the version's first option. The key is derived from the
 * integrity key ({@code JABIZ_INTEGRITY_KEY}) under its own label, so it signs nothing else; the tests' fixed integrity
 * key keeps their orders the same from run to run.
 */
public final class OptionShuffle {

    /** The label the shuffle's key is derived under: HMAC(integrity key, label). */
    static final String LABEL = "qb.option-shuffle";

    private static final String ALGORITHM = "HmacSHA256";
    private static final int MIN_KEY_BYTES = 32;

    private final byte[] key;

    /** @param key the shuffle's own key, at least 32 bytes */
    public OptionShuffle(byte[] key) {
        Objects.requireNonNull(key, "key must not be null");
        if (key.length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException("The shuffle's key must have at least " + MIN_KEY_BYTES + " bytes");
        }
        this.key = key.clone();
    }

    /** The shuffle keyed from the integrity key, under {@value #LABEL}. */
    public static OptionShuffle of(IntegrityKey integrityKey) {
        return new OptionShuffle(HexFormat.of().parseHex(integrityKey.sign(LABEL)));
    }

    /** For each question in order, its options' numbers in the order the attempt shows them. */
    public List<List<Integer>> order(String userId, Object publicationId, List<QuizSnapshot.Question> questions) {
        List<List<Integer>> order = new ArrayList<>();
        for (QuizSnapshot.Question question : questions) {
            List<Integer> numbers = new ArrayList<>(question.options().stream().map(QuizSnapshot.Option::no).toList());
            SplittableRandom random = new SplittableRandom(seed(userId, publicationId, question.no()));
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

    /** The first 64 bits of HMAC-SHA256(key, {@code userId|publicationId|questionNo}). */
    long seed(String userId, Object publicationId, int questionNo) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            byte[] digest = mac.doFinal((userId + "|" + publicationId + "|" + questionNo)
                .getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest, 0, Long.BYTES).getLong();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }

    @Override
    public String toString() {
        return "OptionShuffle[key=***]";
    }
}
