package com.jabiz.runtime.i18n;

import com.jabiz.entity.i18n.I18nTextSupport;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * {@value com.jabiz.entity.i18n.I18nText#KIND_ID}: the core semantics plus reading the stored {@code jsonb} text
 * (docs/design/09-decisions.md D20). Registered through {@code META-INF/services}.
 */
public final class I18nTextKindSupport extends I18nTextSupport {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Override
    protected Map<String, Object> parseJson(String json) {
        try {
            return JSON.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (JacksonException e) {
            throw new IllegalArgumentException("texts are not a JSON object: " + e.getOriginalMessage(), e);
        }
    }
}
