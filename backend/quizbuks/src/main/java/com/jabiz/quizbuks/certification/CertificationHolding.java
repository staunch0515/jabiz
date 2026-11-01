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

    /** Until when the certification is held at {@code now}; empty when it is not. */
    public static Optional<Instant> heldUntil(List<Entry> entries, Certification certification, Instant now) {
        Instant revoked = entries.stream().filter(e -> REVOKED.equals(e.kind())).map(Entry::at)
            .max(Comparator.naturalOrder()).orElse(null);
        return entries.stream()
            .filter(e -> PASSED.equals(e.kind()) || GRANTED.equals(e.kind()))
            .filter(e -> e.version() == certification.version())
            .filter(e -> revoked == null || e.at().isAfter(revoked))
            .map(Entry::validUntil)
            .filter(until -> until != null && until.isAfter(now))
            .max(Comparator.naturalOrder());
    }

    private CertificationHolding() {}
}
