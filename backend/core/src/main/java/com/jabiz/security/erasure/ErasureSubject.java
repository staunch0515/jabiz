package com.jabiz.security.erasure;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The user an eraser's {@link PersonalDataEraser#notices} are about.
 *
 * @param userId the user's id
 * @param data   the rows of the eraser's {@link PersonalDataEraser#loads()}, by load name
 */
public record ErasureSubject(String userId, Map<String, List<Map<String, Object>>> data) {

    /** Most rows one load reads; more is an error (the platform does not cut lists short). */
    public static final int MAX_ROWS = 100;

    public ErasureSubject {
        Objects.requireNonNull(userId, "userId must not be null");
        data = Map.copyOf(data);
    }

    /** The rows of a load; none when the load is unknown or found nothing. */
    public List<Map<String, Object>> rows(String load) {
        return data.getOrDefault(load, List.of());
    }
}
