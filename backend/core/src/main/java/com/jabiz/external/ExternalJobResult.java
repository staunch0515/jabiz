package com.jabiz.external;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * What one successful attempt of an external job returns (decision D38 items 3–4): the result, stored as JSON and
 * handed to the completion process, the usage the attempt consumed, recorded per attempt for the application's
 * statistics (the platform does no billing), and the files it produced (decision D50 item 3), which the platform
 * stores under the job type's output policy before it records the result.
 *
 * @param value the result, an instance of the job type's result record
 * @param usage quantities by metric name; a metric given twice is added up
 * @param files files produced, at most one per key; only for a type that declares {@code outputFiles}
 */
public record ExternalJobResult<R>(R value, Map<String, Long> usage, List<GeneratedFile> files) {

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
        List<GeneratedFile> produced = new ArrayList<>();
        if (files != null) {
            for (GeneratedFile file : files) {
                Objects.requireNonNull(file, "files must not hold null");
                if (produced.stream().anyMatch(other -> other.key().equals(file.key()))) {
                    throw new IllegalArgumentException("Two generated files have the key " + file.key());
                }
                produced.add(file);
            }
        }
        files = List.copyOf(produced);
    }

    /** A result with usage and no files, as decision D38 had it. */
    public ExternalJobResult(R value, Map<String, Long> usage) {
        this(value, usage, List.of());
    }

    public static <R> ExternalJobResult<R> of(R value) {
        return new ExternalJobResult<>(value, Map.of(), List.of());
    }

    /** This result with {@code quantity} more of {@code metric}. */
    public ExternalJobResult<R> usage(String metric, long quantity) {
        Map<String, Long> more = new LinkedHashMap<>(usage);
        add(more, metric, quantity);
        return new ExternalJobResult<>(value, more, files);
    }

    /**
     * This result with one more file (decision D50 item 3), stored under the job type's output policy as an upload of
     * the requester; the completion process finds its id under {@code key}
     * ({@link ExternalJobCompletion#file(String)}).
     *
     * @param key     {@value GeneratedFile#KEY_PATTERN}, not used by another file of the result
     * @param name    the file's name for downloads; the key when null
     * @param content the bytes; what they are is recognised from them, not from the name
     */
    public ExternalJobResult<R> file(String key, String name, byte[] content) {
        List<GeneratedFile> more = new ArrayList<>(files);
        more.add(new GeneratedFile(key, name, content));
        return new ExternalJobResult<>(value, usage, more);
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

    /** The usage and the files' keys only: the value may be personal. */
    @Override
    public String toString() {
        return "ExternalJobResult[usage=" + usage + ", files=" + files + "]";
    }
}
