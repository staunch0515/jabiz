package com.jabiz.param;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Business parameters as they were in effect at one time (docs/design/04-temporal-append-only.md section 9), put
 * into a process context by the platform step {@code LoadParams} so that computation steps can read them without
 * I/O. Values are the canonical Java values of the parameters' kinds (see {@link ParamKinds}).
 *
 * @param asOf   the time the values were in effect; business rules use the time the business event happened
 * @param values values by parameter key
 */
public record ParamValues(Instant asOf, Map<String, Object> values) {

    public ParamValues {
        Objects.requireNonNull(asOf, "asOf must not be null");
        values = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(values, "values")));
    }

    /**
     * The value of a loaded parameter.
     *
     * @throws IllegalStateException when the key was not loaded, or its value is not of {@code type}
     */
    public <T> T get(String key, Class<T> type) {
        Object value = values.get(key);
        if (value == null) {
            throw new IllegalStateException("Parameter '" + key + "' was not loaded; loaded: " + values.keySet());
        }
        if (!type.isInstance(value)) {
            throw new IllegalStateException("Parameter '" + key + "' is a " + value.getClass().getSimpleName()
                + ", not a " + type.getSimpleName());
        }
        return type.cast(value);
    }
}
