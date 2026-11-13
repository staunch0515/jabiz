package com.jabiz.security.erasure;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The user an eraser's {@link PersonalDataEraser#notices} are about, with what its {@link PersonalDataEraser#loads()}
 * read.
 *
 * @param userId the user's id
 * @param data   the rows of the {@link ErasureLoad.Kind#ROWS} loads, by load name
 * @param counts the counts of the {@link ErasureLoad.Kind#COUNT} loads, by load name
 * @param sums   the totals of the {@link ErasureLoad.Kind#SUM} loads, by load name
 */
public record ErasureSubject(String userId, Map<String, List<Map<String, Object>>> data, Map<String, Long> counts,
    Map<String, BigDecimal> sums) {

    /** Most rows one {@link ErasureLoad.Kind#ROWS} load reads; more is an error (the platform does not cut lists short). */
    public static final int MAX_ROWS = 100;

    public ErasureSubject {
        Objects.requireNonNull(userId, "userId must not be null");
        data = Map.copyOf(data);
        counts = counts == null ? Map.of() : Map.copyOf(counts);
        sums = sums == null ? Map.of() : Map.copyOf(sums);
    }

    /** A subject with rows only (as before phase 17zz22). */
    public ErasureSubject(String userId, Map<String, List<Map<String, Object>>> data) {
        this(userId, data, Map.of(), Map.of());
    }

    /** The rows of a load; none when the load is unknown or found nothing. */
    public List<Map<String, Object>> rows(String load) {
        return data.getOrDefault(load, List.of());
    }

    /**
     * The count of a {@link ErasureLoad#count count} load.
     *
     * @throws IllegalArgumentException when no count load of that name was read: a misspelt name is an error, not 0
     */
    public long count(String load) {
        Long count = counts.get(load);
        if (count == null) {
            throw new IllegalArgumentException("No count load named " + load);
        }
        return count;
    }

    /**
     * The total of a {@link ErasureLoad#sum sum} load (0 when it found no rows).
     *
     * @throws IllegalArgumentException when no sum load of that name was read: a misspelt name is an error, not 0
     */
    public BigDecimal sum(String load) {
        BigDecimal sum = sums.get(load);
        if (sum == null) {
            throw new IllegalArgumentException("No sum load named " + load);
        }
        return sum;
    }
}
