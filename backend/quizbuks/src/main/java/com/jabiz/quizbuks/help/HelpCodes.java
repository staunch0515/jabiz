package com.jabiz.quizbuks.help;

import java.util.List;

/** The error codes of the AI help (F-C10); each has its three-language message. */
public final class HelpCodes {

    /** The AI help is switched off ({@code qb.help.enabled}): the user may hand the question to a person. */
    public static final String DISABLED = "QB_HELP_DISABLED";
    /** The caller asked {@code qb.help.user-daily} questions today already. */
    public static final String DAILY_LIMIT = "QB_HELP_DAILY_LIMIT";
    /** Everybody's tokens of the month reached {@code qb.help.global-monthly-tokens}. */
    public static final String MONTHLY_LIMIT = "QB_HELP_MONTHLY_LIMIT";
    /** The conversation's previous question is still being answered. */
    public static final String WAITING = "QB_HELP_WAITING";
    /** The conversation is closed: start a new one. */
    public static final String CLOSED = "QB_HELP_CLOSED";
    /** The conversation was handed to a person already. */
    public static final String ALREADY_ESCALATED = "QB_HELP_ALREADY_ESCALATED";
    /** A person replies only to a conversation handed to a person. */
    public static final String NOT_ESCALATED = "QB_HELP_NOT_ESCALATED";
    /** The entry asked from is not the caller's (a Taker's or a Sponsor's). */
    public static final String ENTRY = "QB_HELP_ENTRY";
    /** Only an AI answer or a person's reply is rated. */
    public static final String NOT_RATABLE = "QB_HELP_NOT_RATABLE";
    /** A question, a message or a reply is empty. */
    public static final String TEXT_REQUIRED = "QB_HELP_TEXT_REQUIRED";
    /** A question or the words handed over are longer than {@code qb.help.question-max-length}. */
    public static final String TEXT_TOO_LONG = "QB_HELP_TEXT_TOO_LONG";
    /** {@code qb.help.model} names a model the platform does not allow. */
    public static final String MODEL = "QB_HELP_MODEL";

    /** The job failed because the help was asked to answer a quiz (the answer's check, best effort). */
    public static final String QUIZ_REFUSED = "QB_HELP_QUIZ_REFUSED";
    /** An operator stopped the job at the platform. */
    public static final String STOPPED = "QB_HELP_STOPPED";

    /** F-C10b: the manual changed since the administrator read it. */
    public static final String MANUAL_CHANGED = "QB_HELP_MANUAL_CHANGED";
    /** A manual is published in every language the help answers in. */
    public static final String MANUAL_LANGUAGE = "QB_HELP_MANUAL_LANGUAGE";
    /** The materials of an entry and language would be larger than {@code qb.help.manual-max-bytes}. */
    public static final String MATERIALS_TOO_LARGE = "QB_HELP_MATERIALS_TOO_LARGE";
    /** The draft was edited since the repository's text was last loaded: replacing it is asked for explicitly. */
    public static final String MANUAL_EDITED = "QB_HELP_MANUAL_EDITED";
    /** A snapshot's job was given a text that is not the one its hash names (the job fails; the next build retries). */
    public static final String MATERIALS_CHANGED = "QB_HELP_MATERIALS_CHANGED";

    public static final List<String> ALL = List.of(DISABLED, DAILY_LIMIT, MONTHLY_LIMIT, WAITING, CLOSED,
        ALREADY_ESCALATED, NOT_ESCALATED, ENTRY, NOT_RATABLE, TEXT_REQUIRED, TEXT_TOO_LONG, MODEL, QUIZ_REFUSED, STOPPED,
        MANUAL_CHANGED, MANUAL_LANGUAGE, MATERIALS_TOO_LARGE, MANUAL_EDITED, MATERIALS_CHANGED);

    private HelpCodes() {}
}
