package com.jabiz.quizbuks.content;

import com.jabiz.runtime.EntityInstance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * F-S6 (docs/quizbuks/plans/impl/F-S6.md §3.4): what an import batch added of a question - its text, image, points,
 * selection and its options in order (text, image, right or not) - as a hash, computed the same way from the batch
 * and from the question as it is now. Equal means the question was not changed since (a move or a translation does
 * not count), so undoing the batch may remove it.
 */
public final class ImportHash {

    /** Of a question as the batch adds it. */
    public static String of(QuizSnapshot.Question question) {
        StringBuilder text = head(question.stem(), question.image(), question.points(), question.selection());
        question.options().forEach(o -> option(text, o.text(), o.image(), o.correct()));
        return sha256(text);
    }

    /** Of a question as it is now, with its options. */
    public static String of(EntityInstance question, List<EntityInstance> options) {
        StringBuilder text = head(question.get("stem"), question.get("image"),
            (int) QuizEditing.number(question.get("points")), question.get("selection"));
        QuizEditing.ordered(options).forEach(o -> option(text, o.get("text"), o.get("image"),
            Boolean.TRUE.equals(o.get("correct"))));
        return sha256(text);
    }

    private static StringBuilder head(Object stem, Object image, int points, Object selection) {
        StringBuilder text = new StringBuilder();
        field(text, stem);
        field(text, image);
        field(text, points);
        field(text, selection);
        return text;
    }

    private static void option(StringBuilder text, Object optionText, Object image, boolean correct) {
        text.append('|');
        field(text, optionText);
        field(text, image);
        field(text, correct);
    }

    /** Each value with its length, so that no two different lists of values read the same. */
    private static void field(StringBuilder text, Object value) {
        String written = value == null ? "" : String.valueOf(value);
        text.append(value == null ? -1 : written.length()).append(':').append(written).append(';');
    }

    private static String sha256(StringBuilder text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing", e);
        }
    }

    private ImportHash() {}
}
