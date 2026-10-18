package com.jabiz.quizbuks.messaging;

import com.jabiz.entity.Violation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The rules of versioned legal texts (docs/quizbuks/plans/Q7-messages-legal.md, D-Q7-4 and D-Q7-5), in plain Java so
 * that they are tested on their own; the templates {@code qb.system.consent-state} and {@code qb.me.consents} say
 * the same in SQL.
 * <ul>
 *   <li>The version <em>in effect</em> at a time is the highest number among the versions whose effective time is not
 *       after it; there is no state to switch, so a version announced for later takes effect without a job.</li>
 *   <li>The version <em>to accept</em> is the highest number among the versions in effect that require consent (the
 *       first version always does). A user who accepted that version or a later one need not accept again: a
 *       rewording ({@code requiresConsent = false}) asks nobody again.</li>
 *   <li>Without any version in effect nothing is asked (development, tests, a new installation).</li>
 *   <li>A new version takes effect now or later, never before the latest one, and not while another one is announced
 *       (an announcement is not changed); the same text again is no new version.</li>
 * </ul>
 */
public final class LegalRules {

    /** A published version, as the rules see it. */
    public record Version(int number, boolean requiresConsent, Instant effectiveAt, String contentHash) {
        public Version {
            Objects.requireNonNull(effectiveAt, "effectiveAt");
        }
    }

    /** The version in effect at {@code at}: the highest number whose effective time is not after it. */
    public static Optional<Version> current(List<Version> versions, Instant at) {
        return versions.stream().filter(v -> !v.effectiveAt().isAfter(at))
            .max(Comparator.comparingInt(Version::number));
    }

    /** The version announced for after {@code at}, if any (the lowest number taking effect later). */
    public static Optional<Version> upcoming(List<Version> versions, Instant at) {
        return versions.stream().filter(v -> v.effectiveAt().isAfter(at))
            .min(Comparator.comparingInt(Version::number));
    }

    /** The number a user must have accepted at {@code at}; 0 when nothing is in effect. */
    public static int toAccept(List<Version> versions, Instant at) {
        return versions.stream().filter(v -> !v.effectiveAt().isAfter(at))
            .filter(v -> v.requiresConsent() || v.number() == 1)
            .mapToInt(Version::number).max().orElse(0);
    }

    /** Whether a user who accepted {@code accepted} (0: none) must accept again: the number to accept is higher. */
    public static boolean mustAccept(int toAccept, int accepted) {
        return toAccept > 0 && accepted < toAccept;
    }

    /** The latest version published, if any. */
    public static Optional<Version> latest(List<Version> versions) {
        return versions.stream().max(Comparator.comparingInt(Version::number));
    }

    /** The number the next version of a kind gets. */
    public static int nextNumber(List<Version> versions) {
        return latest(versions).map(v -> v.number() + 1).orElse(1);
    }

    /** Whether a new version must require consent: the first one always does. */
    public static boolean requiresConsent(List<Version> versions, boolean asked) {
        return versions.isEmpty() || asked;
    }

    /**
     * Why a new version with the content hash {@code hash}, taking effect at {@code effectiveAt}, cannot be published
     * at {@code now}; none when it can. All problems at once.
     */
    public static List<Violation> publishProblems(List<Version> versions, String hash, Instant effectiveAt,
        Instant now) {
        List<Violation> problems = new ArrayList<>();
        if (effectiveAt.isBefore(now)) {
            problems.add(new Violation("effectiveAt", LegalCodes.EFFECTIVE_PAST, "The effective time is in the past",
                Map.of("effectiveAt", effectiveAt.toString())));
        }
        upcoming(versions, now).ifPresent(upcoming -> problems.add(new Violation("effectiveAt",
            LegalCodes.UPCOMING_EXISTS, "Version " + upcoming.number() + " takes effect at "
            + upcoming.effectiveAt() + "; it is not replaced",
            Map.of("versionNo", upcoming.number(), "effectiveAt", upcoming.effectiveAt().toString()))));
        // Effective times follow the numbers without a check of their own: with nothing announced, the latest version
        // is in effect already (not after now), and a new one takes effect now or later.
        latest(versions).filter(latest -> latest.contentHash().equals(hash)).ifPresent(latest -> problems.add(
            new Violation(null, LegalCodes.UNCHANGED, "The text is that of version " + latest.number(),
                Map.of("versionNo", latest.number()))));
        return List.copyOf(problems);
    }

    /**
     * Which of {@code required} languages a text of several languages lacks (missing or blank), in the order given.
     */
    public static List<String> missingLanguages(Map<?, ?> text, List<String> required) {
        List<String> missing = new ArrayList<>();
        for (String language : required) {
            Object value = text == null ? null : text.get(language);
            if (value == null || value.toString().isBlank()) {
                missing.add(language);
            }
        }
        return List.copyOf(missing);
    }

    private LegalRules() {}
}
