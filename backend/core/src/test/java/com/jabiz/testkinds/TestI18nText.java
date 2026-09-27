package com.jabiz.testkinds;

import com.jabiz.entity.CustomKinds;
import com.jabiz.entity.i18n.I18nTextSupport;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * The multilingual text kind for core tests: the core semantics with the JSON parsing the runtime adds
 * (decision D20), so the shared validation cases run without the runtime.
 */
public final class TestI18nText extends I18nTextSupport {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    static {
        CustomKinds.register(new TestI18nText());
    }

    /** Makes sure the kind is registered before a definition using it is exported or validated. */
    public static void register() {
        // Loading the class runs the static registration.
    }

    @Override
    protected Map<String, Object> parseJson(String json) {
        try {
            return JSON.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (JacksonException e) {
            throw new IllegalArgumentException("texts are not a JSON object: " + e.getOriginalMessage(), e);
        }
    }
}
