package com.jabiz.quizbuks.verification;

import com.jabiz.mail.MailCategory;
import com.jabiz.mail.MailTemplate;

import java.time.Duration;

/**
 * The mail of the company verification (F-M3): subject, body and name are the messages {@code mail.<name>.*} in the
 * three languages. The link's token is drawn when the mail is sent and is good once, for the latest mail of the
 * Sponsor only (a resend supersedes the earlier links). It is valid at most {@link #MAX_VALIDITY}; the parameter
 * {@code qb.sponsor-verification.link-hours} shortens it, checked when the link is used.
 */
public final class VerificationMails {

    /** The token's purpose: proving a company's mailbox ({@code {qb.companyEmail}} in the body). */
    public static final String PURPOSE = "qb.companyEmail";
    /** The longest a link may be good for (the parameter's upper bound). */
    public static final Duration MAX_VALIDITY = Duration.ofHours(168);

    /** To an address on the company's domain: open the link, signed in, to prove the mailbox is the company's. */
    public static final MailTemplate CONFIRM = MailTemplate.define("qb.sponsor-verification.confirm", t -> t
        .category(MailCategory.TRANSACTIONAL).param("companyName", "domain", "hours").token(PURPOSE, MAX_VALIDITY));

    private VerificationMails() {
    }
}
