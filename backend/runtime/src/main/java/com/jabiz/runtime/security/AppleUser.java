package com.jabiz.runtime.security;

import com.nimbusds.jose.util.JSONObjectUtils;

import java.text.ParseException;
import java.util.Map;

/**
 * The {@code user} field Apple posts with its first answer to an app (decision D67 item 3):
 * {@code {"name": {"firstName": "…", "lastName": "…"}, "email": "…"}}. Only the name is taken; the address is the ID
 * token's, which Apple signs. Pure.
 */
final class AppleUser {

    private AppleUser() {}

    /**
     * The person's name, as a token's name is cleaned ({@link IdTokenValidator#name}), or null: given and family name
     * with a space between, or the family name first and no space when either is written in Chinese or Japanese
     * characters, as such names are written. Anything unreadable or larger than {@link OidcController#MAX_USER_FIELD}
     * is no name.
     */
    static String name(String field) {
        if (field == null || field.isBlank() || field.length() > OidcController.MAX_USER_FIELD) {
            return null;
        }
        try {
            if (!(JSONObjectUtils.parse(field).get("name") instanceof Map<?, ?> name)) {
                return null;
            }
            String given = text(name.get("firstName"));
            String family = text(name.get("lastName"));
            if (given == null || family == null) {
                return IdTokenValidator.name(given == null ? family : given);
            }
            return IdTokenValidator.name(cjk(given) || cjk(family) ? family + given : given + " " + family);
        } catch (ParseException e) {
            return null;
        }
    }

    private static String text(Object value) {
        return value instanceof String text && !text.isBlank() ? text.strip() : null;
    }

    /** Whether the text has Han, Hiragana or Katakana characters. */
    static boolean cjk(String text) {
        return text.codePoints().anyMatch(c -> {
            Character.UnicodeScript script = Character.UnicodeScript.of(c);
            return script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA;
        });
    }
}
