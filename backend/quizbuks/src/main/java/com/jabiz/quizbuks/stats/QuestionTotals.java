package com.jabiz.quizbuks.stats;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Each version's attempts read and its questions' completions and right answers so far, as the cursor keeps them:
 * canonical JSON {@code {"<version>": {"n": attempts, "q": [[no, completions, correct], ...]}, ...}}, versions in the
 * order of their keys and questions in their order. Per version (Q51b): a publication that switched its version asks
 * other questions under the same numbers. Kept in the cursor's one row rather than a row per question, so that a run
 * writes one row per publication however many questions it has (the audit records every write); the rows of
 * {@code QbQuestionStat} change only when the shown figures do.
 */
public final class QuestionTotals {

    /** The column's length: a hundred versions of 100 questions of up to nine-digit counts fit. */
    public static final int MAX_LENGTH = 400_000;

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Stored>> VERSIONS = new TypeReference<>() {};

    /** One question's completions and right answers. */
    public record Count(long completions, long correct) {
        public Count plus(Count other) {
            return new Count(completions + other.completions, correct + other.correct);
        }
    }

    /** One version's attempts read (with a result or not) and its questions' counts. */
    public record Version(long attempts, SortedMap<Integer, Count> questions) {

        public Version {
            questions = new TreeMap<>(questions);
        }

        public static Version empty() {
            return new Version(0, new TreeMap<>());
        }

        public Version plus(Version other) {
            SortedMap<Integer, Count> sum = new TreeMap<>(questions);
            other.questions.forEach((no, count) -> sum.merge(no, count, Count::plus));
            return new Version(attempts + other.attempts, sum);
        }
    }

    private record Stored(long n, List<List<Long>> q) {}

    /** The totals in the JSON by version (none for null, empty or {@code {}}). */
    public static SortedMap<String, Version> parse(String json) {
        SortedMap<String, Version> totals = new TreeMap<>();
        if (json == null || json.isBlank()) {
            return totals;
        }
        JSON.readValue(json, VERSIONS).forEach((versionId, stored) -> {
            SortedMap<Integer, Count> questions = new TreeMap<>();
            if (stored.q() != null) {
                for (List<Long> row : stored.q()) {
                    questions.put(row.get(0).intValue(), new Count(row.get(1), row.get(2)));
                }
            }
            totals.put(versionId, new Version(stored.n(), questions));
        });
        return totals;
    }

    /** The canonical JSON of the totals. */
    public static String format(Map<String, Version> totals) {
        Map<String, Map<String, Object>> versions = new LinkedHashMap<>();
        new TreeMap<>(totals).forEach((versionId, version) -> {
            List<List<Long>> rows = new ArrayList<>();
            version.questions().forEach((no, count) -> rows.add(List.of((long) no, count.completions(),
                count.correct())));
            Map<String, Object> stored = new LinkedHashMap<>();
            stored.put("n", version.attempts());
            stored.put("q", rows);
            versions.put(versionId, stored);
        });
        String json = JSON.writeValueAsString(versions);
        if (json.length() > MAX_LENGTH) {
            throw new IllegalStateException("Question totals of " + totals.size() + " versions exceed "
                + MAX_LENGTH + " characters");
        }
        return json;
    }

    /** The totals with the increments added, version by version. */
    public static SortedMap<String, Version> add(Map<String, Version> totals, Map<String, Version> increments) {
        SortedMap<String, Version> sum = new TreeMap<>(totals);
        increments.forEach((versionId, version) -> sum.merge(versionId, version, Version::plus));
        return sum;
    }

    private QuestionTotals() {}
}
