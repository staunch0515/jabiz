package com.jabiz.quizbuks.certification;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformLanguages;
import com.jabiz.quizbuks.certification.Certifications.Certification;
import com.jabiz.runtime.EntityInstance;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A sponsor's certifications as the rules read them (Q88a, D-Q88-1, D-Q88-2): their key in the tables that hold
 * both the catalog's ({@link Certifications}) and the sponsors' ({@code sp:<certificationId>}), the
 * {@link Certification} a row stands for (the holding rule {@link CertificationHolding} is the same), the name in a
 * reader's language, and what a sponsor may define. Pure: the processes and the templates' tests ask here.
 */
public final class SponsorCertifications {

    /** The key prefix: the catalog's keys are bare words, a sponsor's is this plus the row's id (38 characters). */
    public static final String PREFIX = "sp:";

    public static final int NAME_LENGTH = 100;
    public static final int DESCRIPTION_LENGTH = 500;
    public static final int MIN_VALIDITY_DAYS = 30;
    public static final int MAX_VALIDITY_DAYS = 3650;
    public static final int DEFAULT_VALIDITY_DAYS = 365;
    /** A sponsor defines at most this many (active or retired). */
    public static final int MAX_PER_SPONSOR = 20;

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

    /**
     * A multilingual text in {@code language}, else in the platform's languages' order (ja, en, zh as the legal texts
     * fall back), else any; null when there is none.
     */
    public static String text(Map<?, ?> texts, String language) {
        if (texts == null || texts.isEmpty()) {
            return null;
        }
        List<String> order = new ArrayList<>();
        if (language != null) {
            order.add(language);
        }
        order.addAll(List.of("ja", "en", "zh"));
        order.addAll(PlatformLanguages.CODES);
        for (String code : order) {
            Object value = texts.get(code);
            if (value != null && !value.toString().isBlank()) {
                return value.toString();
            }
        }
        return texts.values().stream().filter(v -> v != null && !v.toString().isBlank()).map(Object::toString)
            .findFirst().orElse(null);
    }

    /**
     * What a sponsor's certification must be: a name in at least one language (each within {@link #NAME_LENGTH}), a
     * description within {@link #DESCRIPTION_LENGTH} when given, a validity of {@link #MIN_VALIDITY_DAYS} to
     * {@link #MAX_VALIDITY_DAYS} days. Every problem at once.
     */
    public static List<Violation> problems(Map<?, ?> name, Map<?, ?> description, Integer validityDays) {
        List<Violation> problems = new ArrayList<>();
        if (text(name, null) == null) {
            problems.add(new Violation("name", CertificationCodes.SPONSOR_NAME_REQUIRED, "A name in at least one "
                + "language is required", Map.of()));
        }
        problems.addAll(lengths(name, "name", NAME_LENGTH));
        problems.addAll(lengths(description, "description", DESCRIPTION_LENGTH));
        if (validityDays == null || validityDays < MIN_VALIDITY_DAYS || validityDays > MAX_VALIDITY_DAYS) {
            problems.add(new Violation("validityDays", CertificationCodes.SPONSOR_VALIDITY_RANGE, "The validity "
                + "must be between " + MIN_VALIDITY_DAYS + " and " + MAX_VALIDITY_DAYS + " days",
                Map.of("min", MIN_VALIDITY_DAYS, "max", MAX_VALIDITY_DAYS)));
        }
        return List.copyOf(problems);
    }

    private static List<Violation> lengths(Map<?, ?> texts, String field, int max) {
        List<Violation> problems = new ArrayList<>();
        if (texts == null) {
            return problems;
        }
        for (Map.Entry<?, ?> entry : texts.entrySet()) {
            String language = String.valueOf(entry.getKey());
            if (!PlatformLanguages.CODES.contains(language)) {
                problems.add(new Violation(field + "." + language, CertificationCodes.SPONSOR_LANGUAGE,
                    "Not one of the platform's languages", Map.of("language", language)));
            } else if (entry.getValue() != null && entry.getValue().toString().codePointCount(0,
                entry.getValue().toString().length()) > max) {
                problems.add(new Violation(field + "." + language, CertificationCodes.SPONSOR_TEXT_TOO_LONG,
                    "At most " + max + " characters", Map.of("max", max, "language", language)));
            }
        }
        return problems;
    }

    public static int number(Object value) {
        return value instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(value));
    }
}
