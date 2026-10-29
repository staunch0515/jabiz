package com.jabiz.quizbuks.prospect;

import java.util.List;

/**
 * The refusal codes of AI prospecting (docs/quizbuks/plans/Q48-operators.md, Q48c), each with a message in the three
 * languages. Another owner's prospect or direction is 404 without saying whose.
 */
public final class ProspectCodes {

    /** The settings, the direction or the draft was changed since the page read it: read it again. */
    public static final String CHANGED = "QB_PROSPECT_CHANGED";
    /** A field is not in its form: an e-mail address, a domain, a country or a language code, a kind or a reason. */
    public static final String FIELD = "QB_PROSPECT_FIELD";
    /** The owner has its most directions of search. */
    public static final String DIRECTIONS_FULL = "QB_PROSPECT_DIRECTIONS_FULL";
    /** The business's address or domain is blocked: it is never written to. */
    public static final String BLOCKED = "QB_PROSPECT_BLOCKED";
    /** The mail is plain text without links or addresses (the trial's link and the sender are added after it). */
    public static final String MAIL_BODY = "QB_PROSPECT_MAIL_BODY";
    /** The trial quiz has five questions of four options, exactly one of them right. */
    public static final String TRIAL = "QB_PROSPECT_TRIAL";
    /** The business is not in a state for this (a mail is approved only as a draft, discarded only before it is sent). */
    public static final String STATE = "QB_PROSPECT_STATE";

    public static final List<String> ALL = List.of(CHANGED, FIELD, DIRECTIONS_FULL, BLOCKED, MAIL_BODY, TRIAL, STATE);

    private ProspectCodes() {}
}
