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

    public static final List<String> ALL = List.of(DISABLED, DAILY_LIMIT, MONTHLY_LIMIT, WAITING, CLOSED,
        ALREADY_ESCALATED, NOT_ESCALATED, ENTRY, NOT_RATABLE, TEXT_REQUIRED, TEXT_TOO_LONG, MODEL, QUIZ_REFUSED, STOPPED);

    private HelpCodes() {}
}
