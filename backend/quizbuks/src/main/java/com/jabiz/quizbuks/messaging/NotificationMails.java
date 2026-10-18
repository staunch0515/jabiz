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
 *       {@link #FUNDS_SHORT_INTERVAL}, naming all of them) and a publication ended. They are the sponsor console's
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
        .category(MailCategory.TRANSACTIONAL).param("amount", "fee", "net"));

    /** A taker's transfer failed for certain; the money is back on their balance. */
    public static final MailTemplate TRANSFER_FAILED = MailTemplate.define("qb.transfer.failed", t -> t
        .category(MailCategory.TRANSACTIONAL).param("amount", "reason"));

    /** Stripe holds back a taker's payout account that was enabled: they must give what it asks for. */
    public static final MailTemplate PAYOUT_ACTION_NEEDED = MailTemplate.define("qb.payout.action-needed", t -> t
        .category(MailCategory.TRANSACTIONAL));

    /** A sponsor's top-up is on their wallet. */
    public static final MailTemplate TOPUP_PAID = MailTemplate.define("qb.topup.paid", t -> t
        .category(MailCategory.TRANSACTIONAL).param("amount"));

    /** A sponsor's publication passed its review. */
    public static final MailTemplate PUBLICATION_APPROVED = MailTemplate.define("qb.publication.approved", t -> t
        .category(MailCategory.TRANSACTIONAL).param("name", "startAt"));

    /** A sponsor's publication was sent back by its reviewer. */
    public static final MailTemplate PUBLICATION_REJECTED = MailTemplate.define("qb.publication.rejected", t -> t
        .category(MailCategory.TRANSACTIONAL).param("name", "reason"));

    /** An administrator paused or ended a sponsor's publication. */
    public static final MailTemplate PUBLICATION_TAKEN_DOWN = MailTemplate.define("qb.publication.taken-down", t -> t
        .category(MailCategory.TRANSACTIONAL).param("name", "reason"));

    /**
     * A sponsor's wallet could not pay a reward: their publications short of funds take no new attempts until topped
     * up. One mail names them all ({@code publications}).
     */
    public static final MailTemplate PUBLICATION_FUNDS_SHORT = MailTemplate.define("qb.publication.funds-short",
        t -> t.category(MailCategory.NOTIFICATION).entries("sponsor")
            .param("publications", "pendingCount", "pendingAmount"));

    /** A sponsor's publication ended by its time, its winners or its budget. */
    public static final MailTemplate PUBLICATION_ENDED = MailTemplate.define("qb.publication.ended", t -> t
        .category(MailCategory.NOTIFICATION).entries("sponsor").param("name", "reason", "completions"));

    public static final List<MailTemplate> ALL = List.of(TRANSFER_SENT, TRANSFER_FAILED, PAYOUT_ACTION_NEEDED,
        TOPUP_PAID, PUBLICATION_APPROVED, PUBLICATION_REJECTED, PUBLICATION_TAKEN_DOWN, PUBLICATION_FUNDS_SHORT,
        PUBLICATION_ENDED);

    /**
     * At most one "short of funds" mail per sponsor in this time: a wallet may run dry and be topped up often, and a dry
     * wallet leaves every live publication of the sponsor short.
     */
    public static final Duration FUNDS_SHORT_INTERVAL = Duration.ofHours(24);

    /**
     * Events older than this are not mailed: a consumer subscribed later receives every earlier event (platform 11
     * §2.3), and a mail about a transfer of last month would only confuse; so would a delivery stalled for days.
     */
    public static final Duration MAX_AGE = Duration.ofDays(3);

    private NotificationMails() {}
}
