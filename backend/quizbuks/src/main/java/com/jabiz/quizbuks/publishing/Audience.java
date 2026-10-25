package com.jabiz.quizbuks.publishing;

import java.util.List;

/**
 * Who may answer a publication (docs/quizbuks/plans/Q25-invitations.md): everybody in its regions
 * ({@link #PUBLIC}, what every publication was before Q25), or the addresses on its invitation list only
 * ({@link #INVITED}): such a publication is not in the app's feed nor on its public share page, its regions do not
 * apply, and an attempt is started and finished only by a taker whose account claimed an invitation.
 */
public final class Audience {

    public static final String PUBLIC = "PUBLIC";
    public static final String INVITED = "INVITED";
    public static final List<String> ALL = List.of(PUBLIC, INVITED);
    public static final String DICTIONARY = "urn:jabiz:dict:quizbuks:publication-audience";

    /** Whether the audience is {@link #INVITED}; null (a row from before Q25) is {@link #PUBLIC}. */
    public static boolean invited(Object audience) {
        return INVITED.equals(audience);
    }

    /** The audience as written; null or blank is {@link #PUBLIC}. */
    public static String of(Object audience) {
        return audience == null || audience.toString().isBlank() ? PUBLIC : audience.toString();
    }

    private Audience() {}
}
