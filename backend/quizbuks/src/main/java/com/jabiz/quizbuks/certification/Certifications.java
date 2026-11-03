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

    /**
     * Q86 (docs/quizbuks/plans/Q86-agreement-quiz-tree.md): understanding an agreement, earned by answering a quiz about
     * it after consenting to it (consent itself is unchanged: this is a voluntary, verifiable record). A year; the
     * version is raised by hand when a version of the legal text that requires consent again is published
     * ({@link #legalKinds}; {@code CertificationLegalCheck} warns while it lags).
     */
    public static final Certification TERMS_APP_UNDERSTOOD = new Certification("TERMS_APP_UNDERSTOOD",
        Duration.ofDays(365), 1);
    public static final Certification CONTEST_RULES_UNDERSTOOD = new Certification("CONTEST_RULES_UNDERSTOOD",
        Duration.ofDays(365), 1);
    public static final Certification TERMS_SPONSOR_UNDERSTOOD = new Certification("TERMS_SPONSOR_UNDERSTOOD",
        Duration.ofDays(365), 1);

    /**
     * Q89 (docs/quizbuks/plans/Q89-sponsored-benefits.md, D-Q89-1): a sponsor's capabilities in sponsored benefits,
     * one each (the requirement owner: "能分就分"): holders list goods, services or digital products other sponsors may
     * buy for their takers ({@code BENEFIT_PROVIDER}), or buy them and offer them as rewards ({@code BENEFIT_SPONSOR}).
     * A year, like the others; what was made while one was held keeps running after it ends.
     */
    public static final Certification BENEFIT_PROVIDER = new Certification("BENEFIT_PROVIDER", Duration.ofDays(365), 1);
    public static final Certification BENEFIT_SPONSOR = new Certification("BENEFIT_SPONSOR", Duration.ofDays(365), 1);

    public static final List<Certification> ALL = List.of(SPONSOR_APPLY, REFERRER, TERMS_APP_UNDERSTOOD,
        CONTEST_RULES_UNDERSTOOD, TERMS_SPONSOR_UNDERSTOOD, BENEFIT_PROVIDER, BENEFIT_SPONSOR);

    /** Q89: the sponsors' capabilities in sponsored benefits (not a taker's prerequisite). */
    public static final List<Certification> BENEFITS = List.of(BENEFIT_PROVIDER, BENEFIT_SPONSOR);

    /** The certifications of takers: those a sponsor's publication may require (Q86, D-Q86-5). */
    public static final List<Certification> FOR_TAKERS = List.of(SPONSOR_APPLY, REFERRER, TERMS_APP_UNDERSTOOD,
        CONTEST_RULES_UNDERSTOOD);

    /** The understanding certifications (Q86), the agreements each stands for, in the order the cards show them. */
    public static final List<Certification> UNDERSTANDING = List.of(TERMS_APP_UNDERSTOOD, CONTEST_RULES_UNDERSTOOD,
        TERMS_SPONSOR_UNDERSTOOD);

    /** The kinds of legal text (QbLegal) a certification stands for understanding; empty for the others. */
    public static List<String> legalKinds(Certification certification) {
        return switch (certification.key()) {
            case "TERMS_APP_UNDERSTOOD" -> List.of("TERMS_APP", "PRIVACY");
            case "CONTEST_RULES_UNDERSTOOD" -> List.of("CONTEST_RULES");
            case "TERMS_SPONSOR_UNDERSTOOD" -> List.of("TERMS_SPONSOR", "PRIVACY", "COMMERCE_NOTICE");
            default -> List.of();
        };
    }

    public static boolean forTakers(String key) {
        return FOR_TAKERS.stream().anyMatch(c -> c.key().equals(key));
    }

    public static final String DICTIONARY = "urn:jabiz:dict:quizbuks:certification";

    /**
     * The catalog as the templates write it (qb.app.feed, qb.app.publication and the others
     * CertificationTemplatesTest names): a {@code VALUES} list of key, version and validity in seconds, and (Q88a,
     * D-Q88-2) the sponsors' own certifications after it under their keys {@code sp:<id>}, as one derived table
     * {@code cat(cert, ver, secs)}. The sponsors' rows are the entity's table, so a template that writes this
     * declares {@code QbSponsorCertification} among its entities.
     */
    public static String sqlValues() {
        return ALL.stream().map(c -> "('" + c.key() + "', " + c.version() + ", " + c.validity().toSeconds() + ")")
            .collect(java.util.stream.Collectors.joining(", ", "(SELECT * FROM (VALUES ", ") AS v(cert, ver, secs)"
                + " UNION ALL SELECT '" + SponsorCertifications.PREFIX
                + "' || sc.{{QbSponsorCertification.certificationId}},"
                + " sc.{{QbSponsorCertification.certificationVersion}},"
                + " sc.{{QbSponsorCertification.validityDays}} * 86400 FROM {{QbSponsorCertification}} sc)"));
    }

    public static Optional<Certification> of(String key) {
        return ALL.stream().filter(c -> c.key().equals(key)).findFirst();
    }

    public static String[] keys() {
        return ALL.stream().map(Certification::key).toArray(String[]::new);
    }

    private Certifications() {}
}
