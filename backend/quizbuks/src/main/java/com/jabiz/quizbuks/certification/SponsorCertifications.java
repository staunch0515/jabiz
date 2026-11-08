package com.jabiz.quizbuks.certification;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The certifications Sponsors defined before F-S2c2 (Q88a, D6): their key in the certificates' table
 * ({@code sp:<certificationId>}, the catalog's were bare words) and their names. Nobody defines one any more and
 * nothing is held of them (F-S2c2b, D1); the rows stay, read only, for the certificates issued of them (D8).
 */
public final class SponsorCertifications {

    /** The key prefix: the catalog's keys are bare words, a sponsor's is this plus the row's id (38 characters). */
    public static final String PREFIX = "sp:";

    private SponsorCertifications() {}

    public static boolean isSponsor(String key) {
        return key != null && key.startsWith(PREFIX);
    }

    public static String keyOf(Object certificationId) {
        return PREFIX + certificationId;
    }

    /** The row's id a key names; null for the catalog's keys. */
    public static String idOf(String key) {
        return isSponsor(key) ? key.substring(PREFIX.length()) : null;
    }

    /** The texts without blank ones; null when none is left. */
    static Map<String, String> texts(Object given) {
        if (!(given instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, String> texts = new LinkedHashMap<>();
        map.forEach((language, text) -> {
            if (text != null && !text.toString().isBlank()) {
                texts.put(String.valueOf(language), text.toString().strip());
            }
        });
        return texts.isEmpty() ? null : Map.copyOf(texts);
    }
}
