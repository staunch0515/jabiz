package com.jabiz.runtime.approval;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The JSON of stored conditions, levels, facts and change values. Decimals are read as {@link java.math.BigDecimal}
 * so that an amount read back is the amount written.
 */
final class ApprovalJson {

    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .build();

    private ApprovalJson() {}

    /** The tree of {@code text} (maps, lists, numbers, strings, booleans); null for null or blank text. */
    static Object read(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return JSON.readValue(text, Object.class);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("not valid JSON: " + e.getOriginalMessage());
        }
    }

    static String write(Object value) {
        return JSON.writeValueAsString(value);
    }
}
