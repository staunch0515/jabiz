package com.jabiz.quizbuks.messaging;

import java.util.List;

/**
 * The error codes of the inbox, announcements and notices (docs/quizbuks/plans/Q7-messages-legal.md, Q7c), each with
 * a message in the three languages. All are refusals of processes (422) but {@link #REGIONS_FORMAT}, a field rule
 * (400).
 */
public final class BroadcastCodes {

    /** The regions are not in their written form ({@code country.Regions}). */
    public static final String REGIONS_FORMAT = "QB_BROADCAST_REGIONS_FORMAT";
    /** A value is not one of those allowed (parameters {@code field}, {@code value}). */
    public static final String INVALID_VALUE = "QB_MESSAGING_INVALID_VALUE";
    /** Only a draft is edited or sent (parameter {@code status}). */
    public static final String NOT_DRAFT = "QB_BROADCAST_NOT_DRAFT";
    /** Only a draft or a scheduled one is cancelled (parameter {@code status}). */
    public static final String NOT_SCHEDULED = "QB_BROADCAST_NOT_SCHEDULED";
    /** Only a sent or scheduled one is withdrawn (parameter {@code status}). */
    public static final String NOT_SENT = "QB_BROADCAST_NOT_SENT";
    /** The broadcast was changed since it was read (parameters {@code revision}, {@code baseRevision}). */
    public static final String CHANGED = "QB_BROADCAST_CHANGED";
    /** No title in any language. */
    public static final String TITLE_REQUIRED = "QB_BROADCAST_TITLE_REQUIRED";
    /** No text in any language. */
    public static final String BODY_REQUIRED = "QB_BROADCAST_BODY_REQUIRED";
    /** An announcement of the platform needs an English title and text: the fallback (parameters field, lang). */
    public static final String TRANSLATION_MISSING = "QB_BROADCAST_TRANSLATION_MISSING";
    /** A sponsor's text is longer than a notice may be (parameter {@code max}). */
    public static final String BODY_TOO_LONG = "QB_BROADCAST_BODY_TOO_LONG";
    /** A sponsor's notice names an address: notices are plain text without links (Q7-10). */
    public static final String LINK = "QB_BROADCAST_LINK";
    /** The scheduled time is not in the future. */
    public static final String SCHEDULE_PAST = "QB_BROADCAST_SCHEDULE_PAST";
    /** The scheduled time is too far ahead (parameter {@code days}). */
    public static final String SCHEDULE_TOO_LATE = "QB_BROADCAST_SCHEDULE_TOO_LATE";
    /** The expiry is not after the sending, or too far after it (parameter {@code days}). */
    public static final String EXPIRY = "QB_BROADCAST_EXPIRY";
    /** Aimed at regions without naming any. */
    public static final String REGIONS_REQUIRED = "QB_BROADCAST_REGIONS_REQUIRED";
    /** Announcements to the sponsor console go to everyone or to named sponsors, not to regions. */
    public static final String TARGET_MODE = "QB_BROADCAST_TARGET_MODE";
    /** Aimed at named users without naming any. */
    public static final String USERS_REQUIRED = "QB_BROADCAST_USERS_REQUIRED";
    /** More named users than one announcement may have (parameter {@code max}). */
    public static final String TOO_MANY_USERS = "QB_BROADCAST_TOO_MANY_USERS";
    /** Named users the platform does not have (parameter {@code users}). */
    public static final String UNKNOWN_USERS = "QB_BROADCAST_UNKNOWN_USERS";
    /** A sponsor's notice names none of their publications. */
    public static final String PUBLICATION_REQUIRED = "QB_BROADCAST_PUBLICATION_REQUIRED";
    /** The publication is not one of the sponsor's own scheduled or live ones (parameter {@code status}). */
    public static final String PUBLICATION_NOT_ON = "QB_BROADCAST_PUBLICATION_NOT_ON";
    /** The notice would go out after its publication ends (parameter {@code endAt}). */
    public static final String AFTER_END = "QB_BROADCAST_AFTER_END";
    /** The notice's regions are not among its publication's (parameter {@code regions}). */
    public static final String REGIONS_OUTSIDE = "QB_BROADCAST_REGIONS_OUTSIDE";
    /** The publication had as many notices as it may (parameter {@code max}). */
    public static final String TOO_MANY = "QB_BROADCAST_TOO_MANY";
    /** Another notice of the publication goes out within the interval (parameters {@code hours}, {@code at}). */
    public static final String TOO_SOON = "QB_BROADCAST_TOO_SOON";
    /** Withdrawing without a reason (the administrators'; the sponsor reads it). */
    public static final String REASON_REQUIRED = "QB_BROADCAST_REASON_REQUIRED";

    /** Why the job did not send a scheduled notice: its publication is no longer on. */
    public static final String CANCEL_PUBLICATION_ENDED = "QB_BROADCAST_PUBLICATION_ENDED";
    /** Why a notice was cancelled or withdrawn without a person's reason: its sponsor was banned (the publications'). */
    public static final String CANCEL_SPONSOR_BANNED = "QB_ACCOUNT_SUSPENDED";

    public static final List<String> ALL = List.of(REGIONS_FORMAT, INVALID_VALUE, NOT_DRAFT, NOT_SCHEDULED, NOT_SENT,
        CHANGED, TITLE_REQUIRED, BODY_REQUIRED, TRANSLATION_MISSING, BODY_TOO_LONG, LINK, SCHEDULE_PAST,
        SCHEDULE_TOO_LATE, EXPIRY, REGIONS_REQUIRED, TARGET_MODE, USERS_REQUIRED, TOO_MANY_USERS, UNKNOWN_USERS,
        PUBLICATION_REQUIRED, PUBLICATION_NOT_ON, AFTER_END, REGIONS_OUTSIDE, TOO_MANY, TOO_SOON, REASON_REQUIRED,
        CANCEL_PUBLICATION_ENDED);

    private BroadcastCodes() {}
}
