package com.jabiz.entity.i18n;

import com.jabiz.entity.CustomKindSupport;
import com.jabiz.entity.KindViolation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.i18n.PlatformLanguages;
import com.jabiz.query.QueryOperator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Semantics of {@value I18nText#KIND_ID} (docs/design/16-content-authoring.md section 1): normalization, validation,
 * query operators and export. The core has no JSON library, so reading the stored JSON text is left to a subclass
 * (the runtime's, decision D20); everything the shared validation cases exercise lives here.
 */
public abstract class I18nTextSupport implements CustomKindSupport {

    @Override
    public final String kindId() {
        return I18nText.KIND_ID;
    }

    /**
     * Parses the JSON object text read from storage.
     *
     * @throws IllegalArgumentException if the text is not a JSON object
     */
    protected abstract Map<String, Object> parseJson(String json);

    /**
     * Keeps the texts of supported languages, in platform order, without blank ones; no text at all is null.
     * Input must be an object; stored values may be its JSON text and are taken as they are (a language may have
     * been dropped since they were written).
     */
    @Override
    public Object coerce(Map<String, Object> params, Object raw, boolean forInput) {
        Map<?, ?> map;
        if (raw instanceof Map<?, ?> given) {
            map = given;
        } else if (raw instanceof CharSequence text && !forInput) {
            // Input is always a JSON object; only storage hands over the JSON text.
            map = parseJson(text.toString());
        } else {
            throw new IllegalArgumentException("cannot convert " + raw.getClass().getSimpleName()
                + " to texts by language");
        }
        Map<String, String> texts = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String language = String.valueOf(entry.getKey());
            if (forInput && !PlatformLanguages.isSupported(language)) {
                throw new IllegalArgumentException("'" + language + "' is not a supported language "
                    + PlatformLanguages.CODES);
            }
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            if (!(value instanceof CharSequence text)) {
                throw new IllegalArgumentException("the text of '" + language + "' is not a text");
            }
            if (!forInput || !text.toString().isBlank()) {
                texts.put(language, text.toString());
            }
        }
        if (texts.isEmpty()) {
            return null;
        }
        Map<String, String> ordered = new LinkedHashMap<>();
        PlatformLanguages.CODES.stream().filter(texts::containsKey).forEach(l -> ordered.put(l, texts.get(l)));
        texts.forEach(ordered::putIfAbsent);
        return Collections.unmodifiableMap(ordered);
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
        Map<String, Object> json = new LinkedHashMap<>();
        json.put(I18nText.FORMAT, params.getOrDefault(I18nText.FORMAT, I18nText.PLAIN));
        json.put(I18nText.MULTILINE, params.getOrDefault(I18nText.MULTILINE, false));
        if (params.get(I18nText.MAX_LENGTH) != null) {
            json.put(I18nText.MAX_LENGTH, params.get(I18nText.MAX_LENGTH));
        }
        json.put(I18nText.REQUIRED, requiredLanguages(params));
        json.put("locales", PlatformLanguages.CODES);
        return json;
    }

    /** Too long texts first (in platform order), then missing required languages. */
    @Override
    public List<KindViolation> validate(Map<String, Object> params, Object value) {
        Map<?, ?> texts = (Map<?, ?>) value;
        List<KindViolation> violations = new ArrayList<>();
        Integer max = maxLength(params);
        if (max != null) {
            for (String language : PlatformLanguages.CODES) {
                if (texts.get(language) instanceof String text && text.codePointCount(0, text.length()) > max) {
                    violations.add(new KindViolation(PlatformErrorCodes.TOO_LONG, Map.of("lang", language, "max", max)));
                }
            }
        }
        for (String language : requiredLanguages(params)) {
            if (!texts.containsKey(language)) {
                violations.add(new KindViolation(PlatformErrorCodes.TRANSLATION_REQUIRED, Map.of("lang", language)));
            }
        }
        return violations;
    }

    @Override
    public List<String> violationCodes(Map<String, Object> params) {
        return List.of(PlatformErrorCodes.TOO_LONG, PlatformErrorCodes.TRANSLATION_REQUIRED);
    }

    private static Integer maxLength(Map<String, Object> params) {
        return params.get(I18nText.MAX_LENGTH) instanceof Number n ? n.intValue() : null;
    }

    private static List<String> requiredLanguages(Map<String, Object> params) {
        if (params.get(I18nText.REQUIRED) instanceof List<?> languages) {
            return languages.stream().map(String::valueOf).toList();
        }
        return List.of();
    }
}
