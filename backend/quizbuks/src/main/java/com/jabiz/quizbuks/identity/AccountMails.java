package com.jabiz.quizbuks.identity;

import com.jabiz.mail.MailCategory;
import com.jabiz.mail.MailTemplate;

/**
 * The mail of accounts (docs/quizbuks/plans/Q2-accounts.md, D-Q2-7; platform decision D35). Subjects and bodies are
 * the messages {@code mail.<name>.subject|body} in the three languages. The verification, "you already have an
 * account" and password reset mails are the platform's.
 *
 * <p>The platform's {@code {baseUrl}} is the administration's address, so a sponsor's mail names the sponsor console
 * by the parameter {@code link} ({@code quizbuks.links.sponsor}). It stands in the text, not in a Markdown link: the
 * platform percent-encodes parameters in link targets, which would break a whole address.
 */
public final class AccountMails {

    /** To a taker who completed their profile for the first time (registration's second step); unsubscribable. */
    public static final MailTemplate WELCOME = MailTemplate.define("qb.welcome", t -> t
        .category(MailCategory.NOTIFICATION).param("name"));

    /** The sponsor's onboarding was approved, by a reviewer or at once. */
    public static final MailTemplate SPONSOR_APPROVED = MailTemplate.define("qb.sponsor.approved", t -> t
        .category(MailCategory.TRANSACTIONAL).param("company", "link"));

    /** The sponsor's onboarding was sent back, with the reviewer's reason. */
    public static final MailTemplate SPONSOR_REJECTED = MailTemplate.define("qb.sponsor.rejected", t -> t
        .category(MailCategory.TRANSACTIONAL).param("company", "reason", "link"));

    private AccountMails() {}
}
