package com.jabiz.quizbuks.certification;

import com.jabiz.quizbuks.certification.Certifications.Certification;
import com.jabiz.runtime.EntityInstance;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A sponsor's certifications as the rules read them (Q88a, D-Q88-1, D-Q88-2): their key in the tables that hold
 * both the catalog's ({@link Certifications}) and the sponsors' ({@code sp:<certificationId>}), the
 * {@link Certification} a row stands for (the holding rule {@link CertificationHolding} is the same) and its name.
 * F-S2c2 (D6): nobody defines one any more; the rows defined before are read for what was held and issued of them.
 */
public final class SponsorCertifications {

    /** The key prefix: the catalog's keys are bare words, a sponsor's is this plus the row's id (38 characters). */
    public static final String PREFIX = "sp:";

    public static final int NAME_LENGTH = 100;
    public static final int DESCRIPTION_LENGTH = 500;
    /** The longest validity one could be defined with (the holdings' SQL reads back this far). */
    public static final int MAX_VALIDITY_DAYS = 3650;

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

    /** The certification a row stands for: its key, validity and current version. */
    public static Certification of(EntityInstance row) {
        return new Certification(keyOf(row.id()), Duration.ofDays(number(row.get("validityDays"))),
            number(row.get("certificationVersion")));
    }

    /** The certification {@code key} names: the catalog's, or the row of {@code rows} it names; empty for neither. */
    public static Optional<Certification> resolve(String key, List<EntityInstance> rows) {
        if (!isSponsor(key)) {
            return Certifications.of(key);
        }
        return rows.stream().filter(r -> key.equals(keyOf(r.id()))).findFirst().map(SponsorCertifications::of);
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

    public static int number(Object value) {
        return value instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(value));
    }
}
