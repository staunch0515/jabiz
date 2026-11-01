package com.jabiz.quizbuks.publishing;

import java.time.Duration;
import java.util.List;

/**
 * The rules a sponsor may choose of who may be referred to their publication (Q70, D-Q70-12): like the kinds of the
 * customer matching, a catalog in code with its texts in the three languages ({@code qb.referee-rule.<code>} in the
 * consoles); the sponsor ticks any of them, none means anybody may be referred. The platform applies the chosen ones
 * and takes no responsibility for the choice (the sponsor terms say so); it adds no rule of its own. Adding a rule is
 * adding it here, to {@code referral.RefereeChecks} and to the texts.
 */
public final class RefereeRules {

    /** Not a regular: whoever passed any publication of this sponsor before is not counted (claim and reward). */
    public static final String NOT_REGULAR = "NOT_REGULAR";
    /** New to the platform: registered within {@link #NEW_TO_PLATFORM_WITHIN} before claiming. */
    public static final String NEW_TO_PLATFORM = "NEW_TO_PLATFORM";
    /** Already meets the publication's customer matching when claiming (Q47). */
    public static final String MATCHES_AUDIENCE = "MATCHES_AUDIENCE";
    /** The referee's profile names the referrer's country. */
    public static final String SAME_REGION_AS_REFERRER = "SAME_REGION_AS_REFERRER";

    public static final List<String> ALL = List.of(NOT_REGULAR, NEW_TO_PLATFORM, MATCHES_AUDIENCE,
        SAME_REGION_AS_REFERRER);

    /** What the sponsors' wizard ticks for a new referral policy (only a default: the sponsor may untick it). */
    public static final List<String> DEFAULT = List.of(NOT_REGULAR);

    public static final Duration NEW_TO_PLATFORM_WITHIN = Duration.ofDays(7);

    public static final String DICTIONARY = "urn:jabiz:dict:quizbuks:referee-rule";

    public static List<String> codes() {
        return ALL;
    }

    private RefereeRules() {}
}
