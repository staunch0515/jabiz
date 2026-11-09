package com.jabiz.quizbuks.play;

import tools.jackson.core.type.TypeReference;
import com.jabiz.integrity.IntegrityKey;
import com.jabiz.quizbuks.content.QuestionDifficulty;
import com.jabiz.quizbuks.content.QuizSnapshot;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SplittableRandom;

/**
 * The questions an attempt is given when its publication draws k of the version's n (Q81,
 * docs/quizbuks/plans/Q81-question-pick.md, D-Q81-3): k of them, drawn once when the attempt starts and stored with
 * it ({@code questionSet}, the version's numbers), in the order {@link QuestionOrder} gives them (F-S7: shuffled, or
 * the version's). F-S7: a publication may also draw by difficulty, k_d of each level. A taker gets one attempt, so
 * one set.
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
    private final QuestionOrder order;

    /**
     * @param key   the pick's own key, at least 32 bytes
     * @param order F-S7: the order the drawn questions are given in
     */
    public QuestionPick(byte[] key, QuestionOrder order) {
        Objects.requireNonNull(key, "key must not be null");
        if (key.length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException("The pick's key must have at least " + MIN_KEY_BYTES + " bytes");
        }
        this.key = key.clone();
        this.order = Objects.requireNonNull(order, "order must not be null");
    }

    /** A pick whose order's key is derived from its own (the tests'). */
    public QuestionPick(byte[] key) {
        this(key, new QuestionOrder(hmac(key, QuestionOrder.LABEL)));
    }

    /** The pick keyed from the integrity key, under {@value #LABEL}, its order under its own label. */
    public static QuestionPick of(IntegrityKey integrityKey) {
        return new QuestionPick(HexFormat.of().parseHex(integrityKey.sign(LABEL)), QuestionOrder.of(integrityKey));
    }

    /**
     * F-S7 (§1, §2): the questions an attempt of the publication on {@code card} is given, in the order it gives them
     * (the version's numbers): the draw first, of the card's {@code pickCount} from the whole version or of each
     * difficulty's count ({@code pickByDifficulty}) from its own questions, then the order: shuffled for this taker
     * when the version shuffles ({@link QuizSnapshot#shuffled()}), else the version's. Null when the attempt is given
     * every question in the version's order (nothing to store, as before F-S7).
     */
    public List<Integer> set(String userId, Object publicationId, QuizSnapshot snapshot,
        com.jabiz.runtime.EntityInstance card) {
        Map<String, Integer> quotas = QuestionDifficulty.parseQuotas(card.get("pickByDifficulty"));
        Object count = card.get("pickCount");
        List<Integer> drawn;
        if (!quotas.isEmpty()) {
            drawn = pickByDifficulty(userId, publicationId, snapshot.questions(), quotas);
        } else if (count != null) {
            drawn = pick(userId, publicationId, snapshot.questions(), ((Number) count).intValue());
        } else if (snapshot.shuffled()) {
            drawn = snapshot.questions().stream().map(QuizSnapshot.Question::no).toList();
        } else {
            return null;
        }
        return order.order(userId, publicationId, drawn, snapshot.shuffled());
    }

    /**
     * F-S7 (§2): the numbers of each difficulty's count of the questions of that difficulty, in the version's order;
     * the levels are drawn one after the other ({@link QuestionDifficulty}'s order) from one sequence of the pick's
     * seed.
     *
     * @throws IllegalArgumentException when a level has fewer questions than its count
     */
    public List<Integer> pickByDifficulty(String userId, Object publicationId, List<QuizSnapshot.Question> questions,
        Map<String, Integer> quotas) {
        SplittableRandom random = new SplittableRandom(seed(userId, publicationId));
        List<Integer> drawn = new ArrayList<>();
        for (QuestionDifficulty level : QuestionDifficulty.values()) {
            int count = quotas.getOrDefault(level.name(), 0);
            if (count == 0) {
                continue;
            }
            List<Integer> of = questions.stream().filter(question -> level.name().equals(question.difficulty()))
                .map(QuizSnapshot.Question::no).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            if (count < 0 || count > of.size()) {
                throw new IllegalArgumentException("The version has " + of.size() + " questions of " + level
                    + ", not " + count);
            }
            shuffle(of, random);
            drawn.addAll(of.subList(0, count));
        }
        drawn.sort(null);
        return List.copyOf(drawn);
    }

    private static void shuffle(List<Integer> list, SplittableRandom random) {
        for (int i = list.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            Integer swap = list.get(i);
            list.set(i, list.get(j));
            list.set(j, swap);
        }
    }

    private static byte[] hmac(byte[] key, String label) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            return mac.doFinal(label.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
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

    /**
     * The set as the attempt stores it (JSON of the version's numbers, in the attempt's order); null for an attempt
     * given every question in the version's order.
     */
    public static String toJson(List<Integer> set) {
        return set == null ? null : Answers.json(set);
    }

    /**
     * The set an attempt stores, in the order it gives them; null when it was given every question in the version's
     * order (also for attempts from before Q81).
     */
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
