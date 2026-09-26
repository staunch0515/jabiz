package com.jabiz.runtime.param;

import com.jabiz.entity.CustomKindSupport;
import com.jabiz.param.ParamKinds;
import com.jabiz.query.QueryOperator;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * {@value #KIND_ID}: the semantic kind of a business parameter, written as data in the notation of SQL template
 * headers ({@code {"type": "numeric", "precision": 5, "scale": 4}}) and stored as JSON (docs/design/04 section 9).
 * Only kinds a parameter may have are accepted ({@link ParamKinds#parse}). Compared as a whole only.
 */
public final class ParamKindSupport implements CustomKindSupport {

    public static final String KIND_ID = "jabiz.param-kind";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Override
    public String kindId() {
        return KIND_ID;
    }

    @Override
    public Object coerce(Map<String, Object> params, Object raw, boolean forInput) {
        Map<String, Object> spec;
        if (raw instanceof Map<?, ?> given) {
            spec = new TreeMap<>();
            given.forEach((key, value) -> spec.put(String.valueOf(key), value));
        } else if (raw instanceof CharSequence text) {
            try {
                spec = new TreeMap<>(JSON.readValue(text.toString(), new TypeReference<Map<String, Object>>() { }));
            } catch (JacksonException e) {
                throw new IllegalArgumentException("a parameter kind is a JSON object: " + e.getOriginalMessage(), e);
            }
        } else {
            throw new IllegalArgumentException("cannot convert " + raw.getClass().getSimpleName()
                + " to a parameter kind");
        }
        ParamKinds.parse(spec);
        return Collections.unmodifiableMap(spec);
    }

    @Override
    public Class<?> javaType(Map<String, Object> params) {
        return Map.class;
    }

    @Override
    public Set<QueryOperator> allowedOperators(Map<String, Object> params) {
        return EnumSet.of(QueryOperator.IS_NULL, QueryOperator.IS_NOT_NULL);
    }

    @Override
    public Map<String, Object> export(Map<String, Object> params) {
        return Map.of("format", "semanticKind");
    }
}
