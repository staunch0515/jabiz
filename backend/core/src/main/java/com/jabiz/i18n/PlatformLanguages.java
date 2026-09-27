package com.jabiz.i18n;

import java.util.List;
import java.util.Locale;

/**
 * Languages the platform supports, in their display order: messages, labels and multilingual content
 * (docs/design/09-decisions.md D20) all use this one set, so interface and content languages cannot drift apart.
 */
public final class PlatformLanguages {

    /** Language codes, in the order clients show and validate them. */
    public static final List<String> CODES = List.of("zh", "ja", "en");

    public static final List<Locale> LOCALES = CODES.stream().map(Locale::of).toList();

    private PlatformLanguages() {}

    public static boolean isSupported(String code) {
        return CODES.contains(code);
    }
}
