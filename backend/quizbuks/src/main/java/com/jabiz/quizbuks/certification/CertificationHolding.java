package com.jabiz.quizbuks.certification;

import com.jabiz.quizbuks.certification.Certifications.Certification;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Whether a person holds a certification now (Q69, D-Q69-3, D-Q69-4), from their records of it: passes and grants
 * count until their end, under the certification's current version; a revocation outweighs every pass and grant
 * before it, never one after it. Pure: the processes and the sign-up guard read the records and ask here.
 */
public final class CertificationHolding {

    public static final String PASSED = "PASSED";
    public static final String GRANTED = "GRANTED";
    public static final String REVOKED = "REVOKED";

    /** A record as the rule reads it: what it is, under which version, when, and until when it counts. */
    public record Entry(String kind, int version, Instant at, Instant validUntil) {}

    /**
     * A holding (Q77): the pass or grant it comes from ({@link #PASSED} or {@link #GRANTED}), since when, and until
     * when; the one that lasts longest when several count.
     */
    public record Holding(String kind, Instant from, Instant until) {}

    /** Until when the certification is held at {@code now}; empty when it is not. */
    public static Optional<Instant> heldUntil(List<Entry> entries, Certification certification, Instant now) {
        return holding(entries, certification, now).map(Holding::until);
    }

    /** The holding at {@code now}: the pass or grant that counts longest; empty when the certification is not held. */
    public static Optional<Holding> holding(List<Entry> entries, Certification certification, Instant now) {
        Instant revoked = entries.stream().filter(e -> REVOKED.equals(e.kind())).map(Entry::at)
            .max(Comparator.naturalOrder()).orElse(null);
        return entries.stream()
            .filter(e -> PASSED.equals(e.kind()) || GRANTED.equals(e.kind()))
            .filter(e -> e.version() == certification.version())
            .filter(e -> revoked == null || e.at().isAfter(revoked))
            .filter(e -> e.validUntil() != null && e.validUntil().isAfter(now))
            .max(Comparator.comparing(Entry::validUntil))
            .map(e -> new Holding(e.kind(), e.at(), e.validUntil()));
    }

    private CertificationHolding() {}
}
