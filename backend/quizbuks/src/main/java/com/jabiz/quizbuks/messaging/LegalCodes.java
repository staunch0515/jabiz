package com.jabiz.quizbuks.messaging;

import java.util.List;

/**
 * The error codes of terms, privacy and help (docs/quizbuks/plans/Q7-messages-legal.md, Q7b), each with a message in
 * the three languages. All are refusals of processes (422).
 */
public final class LegalCodes {

    /** Publishing: a language the published text must have is missing (parameters {@code field}, {@code lang}). */
    public static final String TRANSLATION_MISSING = "QB_LEGAL_TRANSLATION_MISSING";
    /** Publishing: the draft's title and text are those of the latest version. */
    public static final String UNCHANGED = "QB_LEGAL_UNCHANGED";
    /** Publishing: the effective time is in the past. */
    public static final String EFFECTIVE_PAST = "QB_LEGAL_EFFECTIVE_PAST";
    /** Publishing: a version announced for later exists; it is not replaced (parameter {@code versionNo}). */
    public static final String UPCOMING_EXISTS = "QB_LEGAL_UPCOMING_EXISTS";
    /** Publishing: the draft was edited since the publisher read it. */
    public static final String CHANGED = "QB_LEGAL_CHANGED";
    /**
     * Publishing: the effective time is further ahead than {@code qb.legal.max-announce-days} (parameter
     * {@code days}).
     */
    public static final String EFFECTIVE_TOO_LATE = "QB_LEGAL_EFFECTIVE_TOO_LATE";
    /** Withdrawing: the version is in effect already; a new version replaces it (parameter {@code versionNo}). */
    public static final String WITHDRAW_IN_EFFECT = "QB_LEGAL_WITHDRAW_IN_EFFECT";
    /** Withdrawing: the version is withdrawn already (parameter {@code versionNo}). */
    public static final String WITHDRAWN = "QB_LEGAL_WITHDRAWN";

    /** Completing the profile or applying as a sponsor without accepting the terms. */
    public static final String TERMS_REQUIRED = "QB_TERMS_REQUIRED";
    /** The version accepted is no longer the one in effect (parameters {@code kind}, {@code versionNo}). */
    public static final String TERMS_OUTDATED = "QB_TERMS_OUTDATED";
    /** A money or contract action before accepting the version in effect (parameters {@code kind}, {@code versionNo}). */
    public static final String CONSENT_REQUIRED = "QB_CONSENT_REQUIRED";
    /** Accepting a kind of text the caller's front end does not ask for (parameter {@code kind}). */
    public static final String CONSENT_KIND = "QB_CONSENT_KIND";

    public static final List<String> ALL = List.of(TRANSLATION_MISSING, UNCHANGED, EFFECTIVE_PAST, UPCOMING_EXISTS,
        CHANGED, EFFECTIVE_TOO_LATE, WITHDRAW_IN_EFFECT, WITHDRAWN, TERMS_REQUIRED, TERMS_OUTDATED, CONSENT_REQUIRED,
        CONSENT_KIND);

    private LegalCodes() {}
}
