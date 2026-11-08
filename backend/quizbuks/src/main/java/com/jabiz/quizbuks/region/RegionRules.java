package com.jabiz.quizbuks.region;

import com.jabiz.context.ClientCountry;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Locale;
import java.util.Objects;

/**
 * The rule on foreign videos and mainland China (F-S11, docs/quizbuks/plans/impl/F-S11.md), in one place: a request
 * from mainland China ({@link #mainland}) does not start a publication whose card has a foreign video
 * ({@link #foreign}) unless an exception of headquarters is in force for it or its sponsor ({@link #excepted}).
 * Only this rule goes by the address; the regions a publication aims at, the audience count and its fees go by the
 * country of the taker's profile (A3-19). The templates of the App's pages write the same conditions.
 */
public final class RegionRules {

    /** Mainland China, as the address database and the parameter name it. */
    public static final String MAINLAND = "CN";

    private RegionRules() {
    }

    /**
     * Whether a request counts as from mainland China: by its address's country, or, when the database does not know
     * the address, by the parameter {@code qb.region.unknown-ip-country} ({@code CN} by default; empty: no country).
     */
    public static boolean mainland(ClientCountry country, String unknownAs) {
        if (country != null && country.known()) {
            return country.mainlandChina();
        }
        return unknownAs != null && MAINLAND.equals(unknownAs.strip().toUpperCase(Locale.ROOT));
    }

    /** Whether a card's frozen {@code foreignVideo} keeps mainland China out: none (before its backfill) counts. */
    public static boolean foreign(Object cardForeignVideo) {
        return !Boolean.FALSE.equals(cardForeignVideo);
    }

    /** The day an exception's last day is compared with: the UTC date of the time. */
    public static LocalDate today(Instant time) {
        return LocalDate.ofInstant(time, ZoneOffset.UTC);
    }

    /**
     * An exception as the rule reads it.
     *
     * @param expiresOn its last day (UTC, inclusive); null: until revoked
     */
    public record Exception(String kind, String publicationId, String sponsorId, LocalDate expiresOn,
        boolean revoked) {

        /** Whether it is in force on {@code today}: not revoked and not past its last day. */
        public boolean inForce(LocalDate today) {
            return !revoked && (expiresOn == null || !today.isAfter(expiresOn));
        }

        /** Whether it is for this publication, or for its sponsor. */
        public boolean covers(String publication, String sponsor) {
            return QbRegion.PUBLICATION.equals(kind) ? Objects.equals(publicationId, publication)
                : QbRegion.SPONSOR.equals(kind) && sponsor != null && Objects.equals(sponsorId, sponsor);
        }
    }

    /** Whether an exception in force on {@code today} lets mainland China answer the publication. */
    public static boolean excepted(Collection<Exception> exceptions, LocalDate today, String publicationId,
        String sponsorId) {
        return exceptions.stream().anyMatch(e -> e.inForce(today) && e.covers(publicationId, sponsorId));
    }

    /** Whether a start of this card from this country is refused, given the exceptions read for it. */
    public static boolean refused(Object cardForeignVideo, boolean mainland, Collection<Exception> exceptions,
        LocalDate today, String publicationId, String sponsorId) {
        return mainland && foreign(cardForeignVideo) && !excepted(exceptions, today, publicationId, sponsorId);
    }
}
