package com.jabiz.quizbuks.content;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How hard a question is, as its sponsor says (F-S7 §2, docs/quizbuks/plans/impl/F-S7.md): a fixed set of levels of
 * the platform's. Changing the levels is a change of code; their names are the messages {@code qb.difficulty.<level>}.
 * A question may have none. A publication may draw by difficulty: how many of each level ({@link #parseQuotas}).
 */
public enum QuestionDifficulty {
    EASY, MEDIUM, HARD;

    /** The longest stored draw by difficulty: three levels of up to four digits fit well within it. */
    public static final int QUOTAS_LENGTH = 100;

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    /** The levels' codes, in their order. */
    public static String[] codes() {
        return Arrays.stream(values()).map(Enum::name).toArray(String[]::new);
    }

    /** Whether a code is a level's. */
    public static boolean known(Object code) {
        return code != null && Arrays.stream(values()).anyMatch(level -> level.name().equals(code.toString()));
    }

    /** The levels' codes. */
    public static List<String> all() {
        return List.of(codes());
    }

    /**
     * The draw as stored: how many of each level, in the levels' order, the levels drawn none of left out; null for
     * none or for a draw of none at all.
     */
    public static String formatQuotas(Map<String, Integer> quotas) {
        Map<String, Integer> canonical = canonical(quotas);
        return canonical.isEmpty() ? null : JSON.writeValueAsString(canonical);
    }

    /** The draw stored (none for null or blank): each level's count, in the levels' order. */
    public static Map<String, Integer> parseQuotas(Object json) {
        if (json == null || json.toString().isBlank()) {
            return Map.of();
        }
        Map<String, Integer> quotas = new LinkedHashMap<>();
        JSON.readValue(json.toString(), MAP).forEach((level, count) -> quotas.put(level,
            count instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(count))));
        return canonical(quotas);
    }

    /** The levels with a positive count, in the levels' order; unknown levels kept after them (for the check). */
    public static Map<String, Integer> canonical(Map<String, Integer> quotas) {
        if (quotas == null || quotas.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> canonical = new LinkedHashMap<>();
        for (QuestionDifficulty level : values()) {
            Integer count = quotas.get(level.name());
            if (count != null && count != 0) {
                canonical.put(level.name(), count);
            }
        }
        quotas.forEach((level, count) -> {
            if (!known(level) && count != null && count != 0) {
                canonical.put(level, count);
            }
        });
        return Collections.unmodifiableMap(canonical);
    }

    /** The draw's total: how many questions each taker is given. */
    public static int total(Map<String, Integer> quotas) {
        return quotas.values().stream().mapToInt(Integer::intValue).sum();
    }
}
