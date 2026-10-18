package com.jabiz.quizbuks.stats;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Each question's completions and right answers so far, as the cursor keeps them: canonical JSON
 * {@code [[no, completions, correct], ...]} in question order. Kept in the cursor's one row rather than a row per
 * question, so that a run writes one row per publication however many questions it has (the audit records every
 * write); the rows of {@code QbQuestionStat} change only when the shown figures do.
 */
public final class QuestionTotals {

    /** The column's length: 100 questions of up to nine-digit counts fit. */
    public static final int MAX_LENGTH = 4000;

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<List<Long>>> ROWS = new TypeReference<>() {};

    /** One question's completions and right answers. */
    public record Count(long completions, long correct) {
        public Count plus(Count other) {
            return new Count(completions + other.completions, correct + other.correct);
        }
    }

    /** The totals in the JSON (none for null or empty). */
    public static SortedMap<Integer, Count> parse(String json) {
        SortedMap<Integer, Count> totals = new TreeMap<>();
        if (json == null || json.isBlank()) {
            return totals;
        }
        for (List<Long> row : JSON.readValue(json, ROWS)) {
            totals.put(row.get(0).intValue(), new Count(row.get(1), row.get(2)));
        }
        return totals;
    }

    /** The canonical JSON of the totals. */
    public static String format(Map<Integer, Count> totals) {
        List<List<Long>> rows = new ArrayList<>();
        new TreeMap<>(totals).forEach((no, count) -> rows.add(List.of((long) no, count.completions(),
            count.correct())));
        String json = JSON.writeValueAsString(rows);
        if (json.length() > MAX_LENGTH) {
            throw new IllegalStateException("Question totals of " + totals.size() + " questions exceed "
                + MAX_LENGTH + " characters");
        }
        return json;
    }

    /** The totals with the increments added. */
    public static SortedMap<Integer, Count> add(Map<Integer, Count> totals, Map<Integer, Count> increments) {
        SortedMap<Integer, Count> sum = new TreeMap<>(totals);
        increments.forEach((no, count) -> sum.merge(no, count, Count::plus));
        return sum;
    }

    private QuestionTotals() {}
}
