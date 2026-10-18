package com.jabiz.quizbuks.identity;

import com.jabiz.mail.MailCategory;
import com.jabiz.mail.MailTemplate;

/**
 * The mail of accounts (docs/quizbuks/plans/Q2-accounts.md, D-Q2-7; platform decision D35). Subjects and bodies are
 * the messages {@code mail.<name>.subject|body} in the three languages. The verification, "you already have an
 * account" and password reset mails are the platform's.
 *
 * <p>A sponsor's mail links to the sponsor console by the platform's {@code {entryUrl.sponsor}} (decision D43 item 4):
 * {@code jabiz.mail.base-url} with the entry's app-path, {@code /sponsor/}, taken from configuration as it is (only
 * parameters are percent-encoded in link targets, so a whole address is never passed as one).
 */
public final class AccountMails {

    /**
     * To a taker who completed their profile for the first time (registration's second step); unsubscribable, among the
     * app's mail preferences (platform decision D49).
     */
    public static final MailTemplate WELCOME = MailTemplate.define("qb.welcome", t -> t
        .category(MailCategory.NOTIFICATION).entries("app").param("name"));

    /** The sponsor's onboarding was approved, by a reviewer or at once. */
    public static final MailTemplate SPONSOR_APPROVED = MailTemplate.define("qb.sponsor.approved", t -> t
        .category(MailCategory.TRANSACTIONAL).param("company"));

    /** The sponsor's onboarding was sent back, with the reviewer's reason. */
    public static final MailTemplate SPONSOR_REJECTED = MailTemplate.define("qb.sponsor.rejected", t -> t
        .category(MailCategory.TRANSACTIONAL).param("company", "reason"));

    private AccountMails() {}
}
