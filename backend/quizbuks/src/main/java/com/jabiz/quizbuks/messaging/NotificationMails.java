package com.jabiz.quizbuks.messaging;

import com.jabiz.mail.MailCategory;
import com.jabiz.mail.MailTemplate;

import java.time.Duration;
import java.util.List;

/**
 * The mail QuizBuks sends when something happens to a taker's money or a sponsor's publication (Q7a,
 * docs/quizbuks/plans/Q7-messages-legal.md D-Q7-1, Q7-12; platform decisions D35, D49). Subjects, bodies and the names
 * the preferences show are the messages {@code mail.<name>.subject|body|name} in the three languages.
 *
 * <ul>
 *   <li>Transactional (always sent): to takers, a transfer sent or failed and a payout account Stripe holds back; to
 *       sponsors, a top-up received and a publication approved, sent back or taken down.</li>
 *   <li>Notifications (each sponsor may turn them off): publications short of funds (at most one per sponsor in
 *       {@link #NOTICE_INTERVAL}, naming all of them; one within the window is deferred to its end) and a publication ended. They are the sponsor console's
 *       ({@code entries("sponsor")}): a taker's mail preferences never list them.</li>
 * </ul>
 * No mail for rewards, announcements or changed terms: those are in the app only (Q7-12). Links go to each entry's
 * front end by {@code {entryUrl.app}} / {@code {entryUrl.sponsor}} (decision D43 item 4); amounts ("1,234 Kudos") and
 * times (in {@code jabiz.fiscal-year-zone}, labelled with the zone's short name, "JST") are formatted by the process,
 * the parameters being stored as text ({@link MailValues}).
 */
public final class NotificationMails {

    /** A taker's transfer reached their Stripe account. */
    public static final MailTemplate TRANSFER_SENT = MailTemplate.define("qb.transfer.sent", t -> t
        .category(MailCategory.TRANSACTIONAL).param("amount", "fee", "net")
            .sample("amount", "1,000")
            .sample("fee", "100")
            .sample("net", "900")
            .protectedText());

    /** A taker's transfer failed for certain; the money is back on their balance. */
    public static final MailTemplate TRANSFER_FAILED = MailTemplate.define("qb.transfer.failed", t -> t
        .category(MailCategory.TRANSACTIONAL).param("amount", "reason")
            .sample("amount", "1,000")
            .sample("reason", "The receiving account could not be reached.")
            .protectedText());

    /** Stripe holds back a taker's payout account that was enabled: they must give what it asks for. */
    public static final MailTemplate PAYOUT_ACTION_NEEDED = MailTemplate.define("qb.payout.action-needed", t -> t
        .category(MailCategory.TRANSACTIONAL)
            .protectedText());

    /** A sponsor's top-up is on their wallet. */
    public static final MailTemplate TOPUP_PAID = MailTemplate.define("qb.topup.paid", t -> t
        .category(MailCategory.TRANSACTIONAL).param("amount")
            .sample("amount", "10,000"));

    /** Q87: a sponsor's top-up is in their cash account (not Kudos yet). */
    public static final MailTemplate TOPUP_PAID_CASH = MailTemplate.define("qb.topup.paid-cash", t -> t
        .category(MailCategory.TRANSACTIONAL).param("amount")
            .sample("amount", "10,000")
            .protectedText());

    /** Kudos granted to a sponsor by hand are on their wallet (Q18b): no money was received, so no receipt. */
    public static final MailTemplate TOPUP_GRANTED = MailTemplate.define("qb.topup.granted", t -> t
        .category(MailCategory.TRANSACTIONAL).param("amount")
            .sample("amount", "10,000"));
    /** Q87: a platform fee invoice issued, and a reminder of one overdue (transactional: a bill is not opted out of). */
    public static final MailTemplate FEE_INVOICE_ISSUED = MailTemplate.define("qb.fee.invoice-issued", t -> t
        .category(MailCategory.TRANSACTIONAL).param("invoiceNo", "total", "dueOn")
            .sample("invoiceNo", "INV-0001")
            .sample("total", "1,000")
            .sample("dueOn", "2026-01-31")
            .protectedText());
    public static final MailTemplate FEE_INVOICE_OVERDUE = MailTemplate.define("qb.fee.invoice-overdue", t -> t
        .category(MailCategory.TRANSACTIONAL).param("invoiceNo", "total", "dueOn")
            .sample("invoiceNo", "INV-0001")
            .sample("total", "1,000")
            .sample("dueOn", "2026-01-31")
            .protectedText());

    /**
     * F-M2: a sponsor's platform fee Kudos expire soon (each lot once, at most once per sponsor in 24 hours): what is
     * left of the lots, how many, and when the first expires. The sponsor console's, opted out of in its preferences.
     */
    public static final MailTemplate FEE_CREDIT_EXPIRING = MailTemplate.define("qb.fee-credit.expiring", t -> t
        .category(MailCategory.NOTIFICATION).entries("sponsor").param("amount", "count", "expiresAt")
            .sample("amount", "1,000")
            .sample("count", "2")
            .sample("expiresAt", "2026-01-31"));

    /** A sponsor's publication passed its review. */
    public static final MailTemplate PUBLICATION_APPROVED = MailTemplate.define("qb.publication.approved", t -> t
        .category(MailCategory.TRANSACTIONAL).param("name", "start")
            .sample("name", "Spring quiz")
            .sample("start", "2026-01-01"));

    /** A sponsor's publication was sent back by its reviewer. */
    public static final MailTemplate PUBLICATION_REJECTED = MailTemplate.define("qb.publication.rejected", t -> t
        .category(MailCategory.TRANSACTIONAL).param("name", "reason")
            .sample("name", "Spring quiz")
            .sample("reason", "The prize description is missing."));

    /** An administrator paused or ended a sponsor's publication. */
    public static final MailTemplate PUBLICATION_TAKEN_DOWN = MailTemplate.define("qb.publication.taken-down", t -> t
        .category(MailCategory.TRANSACTIONAL).param("name", "reason")
            .sample("name", "Spring quiz")
            .sample("reason", "Paused for a check."));

    /**
     * A sponsor's wallet could not pay a reward: their publications short of funds take no new attempts until topped
     * up. One mail names them all ({@code publications}).
     */
    public static final MailTemplate PUBLICATION_FUNDS_SHORT = MailTemplate.define("qb.publication.funds-short",
        t -> t.category(MailCategory.NOTIFICATION).entries("sponsor")
            .param("publications", "pendingCount", "pendingAmount")
            .sample("publications", "Spring quiz")
            .sample("pendingCount", "3")
            .sample("pendingAmount", "300")
            .protectedText());

    /** A sponsor's publication ended by its time, its winners or its budget. */
    public static final MailTemplate PUBLICATION_ENDED = MailTemplate.define("qb.publication.ended", t -> t
        .category(MailCategory.NOTIFICATION).entries("sponsor").param("name", "reason", "completions")
            .sample("name", "Spring quiz")
            .sample("reason", "The period ended.")
            .sample("completions", "120"));

    /** F-M8: what is left of the exposures bought for a sponsor's publication is low. */
    public static final MailTemplate PUBLICATION_EXPOSURE_LOW = MailTemplate.define("qb.publication.exposure-low", t -> t
        .category(MailCategory.NOTIFICATION).entries("sponsor").param("name", "left", "purchased")
            .sample("name", "Spring quiz")
            .sample("left", "9,000")
            .sample("purchased", "100,000"));

    public static final List<MailTemplate> ALL = List.of(TRANSFER_SENT, TRANSFER_FAILED, PAYOUT_ACTION_NEEDED,
        TOPUP_PAID, TOPUP_PAID_CASH, TOPUP_GRANTED, FEE_INVOICE_ISSUED, FEE_INVOICE_OVERDUE, FEE_CREDIT_EXPIRING, PUBLICATION_APPROVED, PUBLICATION_REJECTED, PUBLICATION_TAKEN_DOWN, PUBLICATION_FUNDS_SHORT,
        PUBLICATION_ENDED, PUBLICATION_EXPOSURE_LOW);

    /**
     * At most one throttled notice per user and kind in this time: a sponsor's "short of funds" (a wallet may run dry
     * and be topped up often, and a dry wallet leaves every live publication short; one within the window is deferred
     * to its end) and a taker's "payout account needs information" (an account may flip between enabled and held back).
     */
    public static final Duration NOTICE_INTERVAL = Duration.ofHours(24);

    /**
     * Notifications for events older than this are not mailed: a consumer subscribed later receives every earlier
     * event (platform 11 §2.3), and a stale "short of funds" or "ended" would only confuse.
     */
    public static final Duration MAX_AGE = Duration.ofDays(3);

    /**
     * Transactional mail (money, reviews, takedowns) waits longer, as through a delivery backlog of days: each process
     * mails only what still holds (a transfer still sent or failed, an account still held back), and only older events
     * are dropped (a consumer subscribed later receives every earlier one).
     */
    public static final Duration TRANSACTIONAL_MAX_AGE = Duration.ofDays(14);

    private NotificationMails() {}
}
