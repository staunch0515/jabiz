package com.jabiz.runtime.dictionary;

import com.jabiz.entity.CustomKindSupport;
import com.jabiz.query.QueryOperator;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * {@value #KIND_ID}: labels of one item in several languages, {@code {"zh": "...", "ja": "...", "en": "..."}},
 * stored as JSON (the {@code labels} of database dictionaries, docs/design/02-metamodel.md section 5). Keys are
 * lower-case language codes, values are texts. Labels are compared as a whole only.
 */
public final class LabelsKindSupport implements CustomKindSupport {

    public static final String KIND_ID = "jabiz.labels";

    private static final Pattern LANGUAGE = Pattern.compile("[a-z]{2,3}");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Override
    public String kindId() {
        return KIND_ID;
    }

    @Override
    public Object coerce(Map<String, Object> params, Object raw, boolean forInput) {
        Map<?, ?> map;
        if (raw instanceof Map<?, ?> given) {
            map = given;
        } else if (raw instanceof CharSequence text) {
            try {
                map = JSON.readValue(text.toString(), new TypeReference<Map<String, Object>>() { });
            } catch (JacksonException e) {
                throw new IllegalArgumentException("labels are not a JSON object: " + e.getOriginalMessage(), e);
            }
        } else {
            throw new IllegalArgumentException("cannot convert " + raw.getClass().getSimpleName() + " to labels");
        }
        Map<String, String> labels = new TreeMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String language = String.valueOf(entry.getKey());
            if (!LANGUAGE.matcher(language).matches()) {
                throw new IllegalArgumentException("'" + language + "' is not a language code");
            }
            if (!(entry.getValue() instanceof CharSequence label)) {
                throw new IllegalArgumentException("the label of '" + language + "' is not a text");
            }
            labels.put(language, label.toString());
        }
        return Collections.unmodifiableMap(labels);
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
        return Map.of("format", "labels");
    }
}
