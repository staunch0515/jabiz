package com.jabiz.quizbuks.messaging;

import com.jabiz.entity.Violation;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The rules of versioned legal texts (docs/quizbuks/plans/Q7-messages-legal.md, D-Q7-4 and D-Q7-5), in plain Java so
 * that they are tested on their own; the templates {@code qb.system.consent-state}, {@code qb.me.consents} and
 * {@code qb.public.legal} say the same in SQL.
 * <ul>
 *   <li>The version <em>in effect</em> at a time is the highest number among the versions not withdrawn whose
 *       effective time is not after it; there is no state to switch, so a version announced for later takes effect
 *       without a job.</li>
 *   <li>The version <em>to accept</em> is the highest number among the versions in effect that require consent (the
 *       first version always does). A user who accepted that version or a later one need not accept again: a
 *       rewording ({@code requiresConsent = false}) asks nobody again.</li>
 *   <li>Without any version in effect nothing is asked (development, tests, a new installation).</li>
 *   <li>A new version takes effect now or later, never before the latest one, and not while another one is announced
 *       (an announcement is not changed, only withdrawn); the same text again is no new version. It takes effect at
 *       most {@code qb.legal.max-announce-days} ahead, so that no announcement holds a kind back for ever.</li>
 *   <li>An announced version may be withdrawn until it takes effect (review M1): withdrawn, it is as if it had never
 *       been published, except that its number is not given again.</li>
 * </ul>
 */
public final class LegalRules {

    /** A published version, as the rules see it; a withdrawn one counts for its number only. */
    public record Version(int number, boolean requiresConsent, Instant effectiveAt, String contentHash,
        boolean isWithdrawn) {
        public Version {
            Objects.requireNonNull(effectiveAt, "effectiveAt");
        }

        public Version(int number, boolean requiresConsent, Instant effectiveAt, String contentHash) {
            this(number, requiresConsent, effectiveAt, contentHash, false);
        }

        /** The same version, withdrawn. */
        public Version withdrawn() {
            return new Version(number, requiresConsent, effectiveAt, contentHash, true);
        }
    }

    private static Stream<Version> standing(List<Version> versions) {
        return versions.stream().filter(v -> !v.isWithdrawn());
    }

    /** The version in effect at {@code at}: the highest number not withdrawn whose effective time is not after it. */
    public static Optional<Version> current(List<Version> versions, Instant at) {
        return standing(versions).filter(v -> !v.effectiveAt().isAfter(at))
            .max(Comparator.comparingInt(Version::number));
    }

    /** The version announced for after {@code at}, if any (the lowest number not withdrawn taking effect later). */
    public static Optional<Version> upcoming(List<Version> versions, Instant at) {
        return standing(versions).filter(v -> v.effectiveAt().isAfter(at))
            .min(Comparator.comparingInt(Version::number));
    }

    /** The number a user must have accepted at {@code at}; 0 when nothing is in effect. */
    public static int toAccept(List<Version> versions, Instant at) {
        return standing(versions).filter(v -> !v.effectiveAt().isAfter(at))
            .filter(v -> v.requiresConsent() || v.number() == 1)
            .mapToInt(Version::number).max().orElse(0);
    }

    /** Whether a user who accepted {@code accepted} (0: none) must accept again: the number to accept is higher. */
    public static boolean mustAccept(int toAccept, int accepted) {
        return toAccept > 0 && accepted < toAccept;
    }

    /** The latest version published and not withdrawn, if any. */
    public static Optional<Version> latest(List<Version> versions) {
        return standing(versions).max(Comparator.comparingInt(Version::number));
    }

    /** The number the next version of a kind gets: a withdrawn version's number is not given again. */
    public static int nextNumber(List<Version> versions) {
        return versions.stream().mapToInt(Version::number).max().orElse(0) + 1;
    }

    /** Whether a new version must require consent: the first one not withdrawn always does. */
    public static boolean requiresConsent(List<Version> versions, boolean asked) {
        return latest(versions).isEmpty() || asked;
    }

    /** The latest effective time a version published at {@code now} may have. */
    public static Instant horizon(Instant now, int maxAnnounceDays) {
        return now.plus(Duration.ofDays(maxAnnounceDays));
    }

    /**
     * Why a new version with the content hash {@code hash}, taking effect at {@code effectiveAt}, cannot be published
     * at {@code now}, effective times up to {@code horizon} allowed; none when it can. All problems at once.
     */
    public static List<Violation> publishProblems(List<Version> versions, String hash, Instant effectiveAt,
        Instant now, Instant horizon) {
        List<Violation> problems = new ArrayList<>();
        if (effectiveAt.isBefore(now)) {
            problems.add(new Violation("effectiveAt", LegalCodes.EFFECTIVE_PAST, "The effective time is in the past",
                Map.of("effectiveAt", effectiveAt.toString())));
        }
        if (effectiveAt.isAfter(horizon)) {
            long days = Duration.between(now, horizon).toDays();
            problems.add(new Violation("effectiveAt", LegalCodes.EFFECTIVE_TOO_LATE, "The effective time is more "
                + "than " + days + " days ahead", Map.of("days", days)));
        }
        upcoming(versions, now).ifPresent(upcoming -> problems.add(new Violation("effectiveAt",
            LegalCodes.UPCOMING_EXISTS, "Version " + upcoming.number() + " takes effect at "
            + upcoming.effectiveAt() + "; it is not replaced",
            Map.of("versionNo", upcoming.number(), "effectiveAt", upcoming.effectiveAt().toString()))));
        // Effective times follow the numbers without a check of their own: with nothing announced, the latest version
        // is in effect already (not after now), and a new one takes effect now or later. A withdrawn version's text
        // may be published again.
        latest(versions).filter(latest -> latest.contentHash().equals(hash)).ifPresent(latest -> problems.add(
            new Violation(null, LegalCodes.UNCHANGED, "The text is that of version " + latest.number(),
                Map.of("versionNo", latest.number()))));
        return List.copyOf(problems);
    }

    /** Why {@code version} cannot be withdrawn at {@code now}; none when it can: only while announced, once. */
    public static List<Violation> withdrawProblems(Version version, Instant now) {
        if (version.isWithdrawn()) {
            return List.of(new Violation("legalVersionId", LegalCodes.WITHDRAWN, "Version " + version.number()
                + " is withdrawn already", Map.of("versionNo", version.number())));
        }
        if (!version.effectiveAt().isAfter(now)) {
            return List.of(new Violation("legalVersionId", LegalCodes.WITHDRAW_IN_EFFECT, "Version "
                + version.number() + " is in effect: publish a new version instead",
                Map.of("versionNo", version.number())));
        }
        return List.of();
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
