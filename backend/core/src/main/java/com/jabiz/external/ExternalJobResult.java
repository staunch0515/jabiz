package com.jabiz.external;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * What one successful attempt of an external job returns (decision D38 items 3–4): the result, stored as JSON and
 * handed to the completion process, and the usage the attempt consumed, recorded per attempt for the application's
 * statistics (the platform does no billing).
 *
 * @param value the result, an instance of the job type's result record
 * @param usage quantities by metric name; a metric given twice is added up
 */
public record ExternalJobResult<R>(R value, Map<String, Long> usage) {

    static final String USAGE_NAME_PATTERN = "[a-zA-Z][A-Za-z0-9._-]{0,63}";
    /** Names of usage metrics, such as {@code promptTokens}. */
    public static final Pattern USAGE_NAME = Pattern.compile(USAGE_NAME_PATTERN);

    public ExternalJobResult {
        Objects.requireNonNull(value, "value must not be null");
        Map<String, Long> copy = new LinkedHashMap<>();
        if (usage != null) {
            usage.forEach((metric, quantity) -> add(copy, metric, quantity == null ? -1 : quantity));
        }
        usage = Collections.unmodifiableMap(copy);
    }

    public static <R> ExternalJobResult<R> of(R value) {
        return new ExternalJobResult<>(value, Map.of());
    }

    /** This result with {@code quantity} more of {@code metric}. */
    public ExternalJobResult<R> usage(String metric, long quantity) {
        Map<String, Long> more = new LinkedHashMap<>(usage);
        add(more, metric, quantity);
        return new ExternalJobResult<>(value, more);
    }

    /**
     * Adds {@code quantity} of {@code metric} to {@code usage}.
     *
     * @throws IllegalArgumentException when the name is not {@value #USAGE_NAME_PATTERN} or the quantity negative
     */
    public static void add(Map<String, Long> usage, String metric, long quantity) {
        if (metric == null || !USAGE_NAME.matcher(metric).matches()) {
            throw new IllegalArgumentException("Usage metric '" + metric + "' must match " + USAGE_NAME_PATTERN);
        }
        if (quantity < 0) {
            throw new IllegalArgumentException("Usage of " + metric + " must not be negative");
        }
        usage.merge(metric, quantity, Math::addExact);
    }

    /** The usage only: the value may be personal. */
    @Override
    public String toString() {
        return "ExternalJobResult[usage=" + usage + "]";
    }
}
