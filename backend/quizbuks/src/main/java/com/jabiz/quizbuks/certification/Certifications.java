package com.jabiz.quizbuks.certification;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * The certifications (Q69, docs/quizbuks/plans/Q69-certifications.md): what passing certain publications is used for.
 * A certification is not a reward method: the quiz is an ordinary publication with its ordinary reward, and passing one
 * of its rounds lets the taker hold the certification for a while. Only this catalog names them (adding one is a code
 * change with its texts); applying to become a sponsor (Q69) and referring friends (Q70).
 */
public final class Certifications {

    /**
     * One certification: its key, how long a pass or a grant counts, and its version (raised when the rules it stands
     * for change, which makes what was held under an older version count no more).
     */
    public record Certification(String key, Duration validity, int version) {}

    /** Holders may apply to become a sponsor from the app (Q69b): the sponsor entry's signed-in join requires it. */
    public static final Certification SPONSOR_APPLY = new Certification("SPONSOR_APPLY", Duration.ofDays(365), 1);

    /**
     * Holders may make referral links of publications that welcome referrals (Q70, D-Q70-8, D-Q70-9): a year, like the
     * others; links made while it was held stay valid after it ends.
     */
    public static final Certification REFERRER = new Certification("REFERRER", Duration.ofDays(365), 1);

    public static final List<Certification> ALL = List.of(SPONSOR_APPLY, REFERRER);

    public static final String DICTIONARY = "urn:jabiz:dict:quizbuks:certification";

    /**
     * The catalog as the App's templates write it (qb.app.feed, qb.app.publication: a {@code VALUES} list of key,
     * version and validity in seconds), which CertificationTemplatesTest finds in each.
     */
    public static String sqlValues() {
        return ALL.stream().map(c -> "('" + c.key() + "', " + c.version() + ", " + c.validity().toSeconds() + ")")
            .collect(java.util.stream.Collectors.joining(", ", "(VALUES ", ")"));
    }

    public static Optional<Certification> of(String key) {
        return ALL.stream().filter(c -> c.key().equals(key)).findFirst();
    }

    public static String[] keys() {
        return ALL.stream().map(Certification::key).toArray(String[]::new);
    }

    private Certifications() {}
}
